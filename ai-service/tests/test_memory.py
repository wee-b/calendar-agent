import json
from types import SimpleNamespace
from unittest import IsolatedAsyncioTestCase
from unittest.mock import AsyncMock, patch
import httpx
from app.all_graph.nodes.summary_node import SummaryNode
from app.core.config.common.memory import MemorySettings
from app.repository.context_summary import SummarySnapshot
from app.main import app


class SummaryTests(IsolatedAsyncioTestCase):
    def fixtures(self):
        snapshot = SummarySnapshot(8, '旧目标', 20, [
            SimpleNamespace(dialogue_id=30, role='user', content='我习惯晚上学习'),
            SimpleNamespace(dialogue_id=31, role='assistant', content='周末也可以学习'),
        ], tuple(range(32, 52)), 22, 51)
        repo = SimpleNamespace(snapshot=AsyncMock(return_value=snapshot), save=AsyncMock(return_value=True))
        payload = {'summary': '用户习惯晚上学习', 'preferences': [{
            'key': 'study_time', 'content': '用户习惯晚上学习', 'memory_type': 'PREFERENCE_TIME',
            'source_dialogue_id': 30, 'evidence': '我习惯晚上学习', 'confidence': .95}]}
        model = SimpleNamespace(complete=AsyncMock(return_value=json.dumps(payload)))
        return snapshot, repo, model, payload

    async def test_one_model_call_compresses_and_extracts(self):
        snapshot, repo, model, _ = self.fixtures()
        result = await SummaryNode(repo, model)({'user_id': 7, 'session_id': 's'})
        self.assertTrue(result['context_compressed'])
        model.complete.assert_awaited_once()
        self.assertIn('旧目标', model.complete.call_args.args[0][1]['content'])
        args = repo.save.call_args.args
        self.assertEqual((7, 's', snapshot, '用户习惯晚上学习'), args[:4])
        self.assertEqual('study_time', args[4][0][1].key)

    async def test_below_threshold_does_not_call_model_or_write(self):
        _, repo, model, _ = self.fixtures()
        repo.snapshot.return_value = None
        self.assertFalse((await SummaryNode(repo, model)({'user_id': 7, 'session_id': 's'}))['context_compressed'])
        model.complete.assert_not_awaited()
        repo.save.assert_not_awaited()
        settings = MemorySettings(_env_file=None)
        self.assertEqual((50, 20), (settings.summary_trigger_messages, settings.summary_retain_messages))

    async def test_invalid_output_or_assistant_evidence_cannot_update_data(self):
        for mode in ('empty', 'assistant', 'missing', 'upstream'):
            with self.subTest(mode=mode):
                _, repo, model, payload = self.fixtures()
                if mode == 'assistant':
                    payload['preferences'][0].update(source_dialogue_id=31, evidence='周末也可以学习')
                if mode == 'missing':
                    payload['preferences'][0]['evidence'] = '我爱早起'
                model.complete.return_value = '' if mode == 'empty' else json.dumps(payload)
                if mode == 'upstream':
                    model.complete.side_effect = RuntimeError('model failed')
                result = await SummaryNode(repo, model)({'user_id': 7, 'session_id': 's'})
                self.assertIn('summary_error', result)
                repo.save.assert_not_awaited()

    async def test_stale_snapshot_and_disabled_feature(self):
        _, repo, model, _ = self.fixtures()
        repo.save.return_value = False
        self.assertFalse((await SummaryNode(repo, model)({'user_id': 7, 'session_id': 's'}))['context_compressed'])
        repo.snapshot.reset_mock()
        await SummaryNode(repo, model, MemorySettings(enabled=False))({'user_id': 7, 'session_id': 's'})
        repo.snapshot.assert_not_awaited()

    async def test_compression_finishes_before_route_and_business_node(self):
        from app.all_graph.conversation_graph import ConversationGraph
        from app.all_graph.nodes.route_node import RouteDecision
        from app.schemas.statemachine.flow import AgentTurnResult, PendingTask, UserSignal
        from app.schemas.statemachine.transitions import AgentType
        from app.service.chat import ChatService
        from tests.conversation_fakes import MemoryFlowRepository
        order = []
        async def summary(state):
            order.append('summary')
            return {'context_compressed': True}
        async def route(*args):
            order.append('route')
            return RouteDecision(signal=UserSignal.NEW_CHAT)
        async def chat(state, context):
            order.append('chat')
            return AgentTurnResult(reply='完成', completed=True, pending=PendingTask(), dispatch_type='CHAT')
        graph = ConversationGraph({AgentType.CHAT: chat}, user_id=7, session_id='s',
            summary_node=summary, chat_service=ChatService(MemoryFlowRepository()),
            route_agent=SimpleNamespace(route=route))
        with self.assertLogs('app', level='INFO') as logs:
            await graph.run_turn('你好', 'authenticated')
        self.assertEqual(['summary', 'route', 'chat'], order)
        self.assertEqual(3, len(logs.records))
        self.assertNotIn('memory_extract', graph.graph.get_graph().nodes)
        self.assertNotIn('schedule_maintenance', graph.graph.get_graph().nodes)

    async def test_retained_user_preferences_are_extracted_in_same_call(self):
        snapshot, repo, model, payload = self.fixtures()
        snapshot.retained_rows.append(SimpleNamespace(dialogue_id=40, role='user', content='我喜欢早上学习'))
        payload['preferences'][0].update(source_dialogue_id=40, evidence='我喜欢早上学习', content='用户偏好早上学习')
        model.complete.return_value = json.dumps(payload)
        await SummaryNode(repo, model)({'user_id': 7, 'session_id': 's'})
        self.assertEqual(40, repo.save.call_args.args[4][0][0])
        model.complete.assert_awaited_once()

    async def test_memory_routes_require_auth_and_user_scope(self):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
            self.assertEqual(401, (await client.get('/memory')).status_code)
            self.assertEqual(401, (await client.delete('/memory/1')).status_code)
            repo = SimpleNamespace(list_active=AsyncMock(return_value=[]), delete=AsyncMock(return_value=False))
            with patch('app.core.middleware.auth.RedisTokenVerifier') as verifier, \
                 patch('app.api.routers.memory_router.MemoryRepository', return_value=repo):
                verifier.return_value.verify = AsyncMock(return_value=7)
                self.assertEqual(200, (await client.get('/memory', headers={'yvli-token': 'valid'})).status_code)
                self.assertEqual(404, (await client.delete('/memory/5', headers={'yvli-token': 'valid'})).status_code)
                repo.list_active.assert_awaited_once_with(7)
                repo.delete.assert_awaited_once_with(7, 5)
