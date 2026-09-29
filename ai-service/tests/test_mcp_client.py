import json
import unittest

import httpx

from app.helper.mcp_client import JavaMcpClient, McpClientError


class JavaMcpClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_list_tools_reads_java_metadata(self):
        def handle(request: httpx.Request) -> httpx.Response:
            body = json.loads(request.content)
            self.assertEqual("tools/list", body["method"])
            return httpx.Response(200, json={
                "jsonrpc": "2.0", "id": body["id"],
                "result": {"tools": [{
                    "name": "queryDayDetail", "inputSchema": {"type": "object"},
                    "metadata": {"readOnly": True},
                }]},
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http_client:
            async with JavaMcpClient(http_client) as mcp:
                tools = await mcp.list_tools("token")
        self.assertEqual("queryDayDetail", tools[0].name)
        self.assertTrue(tools[0].metadata.readOnly)

    async def test_call_tool_forwards_token_and_decodes_structured_result(self):
        def handle(request: httpx.Request) -> httpx.Response:
            self.assertEqual("secret-token", request.headers["yvli-token"])
            body = json.loads(request.content)
            self.assertEqual("tools/call", body["method"])
            self.assertEqual("queryDayDetail", body["params"]["name"])
            self.assertEqual({"date": "2026-09-28"}, body["params"]["arguments"])
            return httpx.Response(200, json={
                "jsonrpc": "2.0", "id": body["id"],
                "result": {"isError": False, "content": [{"type": "text", "text": json.dumps({
                    "date": "2026-09-28", "todos": [], "dailyNote": "休息"
                })}]},
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http_client:
            async with JavaMcpClient(http_client) as mcp:
                result = await mcp.call_tool(
                    "queryDayDetail", {"date": "2026-09-28"}, "secret-token"
                )
        self.assertEqual("休息", result["dailyNote"])

    async def test_tool_error_preserves_code_and_retryable_flag(self):
        def handle(request: httpx.Request) -> httpx.Response:
            request_id = json.loads(request.content)["id"]
            return httpx.Response(200, json={
                "jsonrpc": "2.0", "id": request_id,
                "result": {"isError": True, "content": [{"type": "text", "text": json.dumps({
                    "code": "BUSINESS_404", "message": "待办不存在", "retryable": False
                })}]},
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http_client:
            async with JavaMcpClient(http_client) as mcp:
                with self.assertRaises(McpClientError) as caught:
                    await mcp.call_tool("queryDayDetail", {"date": "2026-09-28"}, "token")
        self.assertEqual("BUSINESS_404", caught.exception.code)
        self.assertFalse(caught.exception.retryable)

    async def test_http_200_auth_error_is_not_treated_as_rpc_result(self):
        transport = httpx.MockTransport(lambda _: httpx.Response(
            200, json={"code": 401, "ok": False, "msg": "登录已过期", "data": None}
        ))
        async with httpx.AsyncClient(transport=transport) as http_client:
            async with JavaMcpClient(http_client) as mcp:
                with self.assertRaises(McpClientError) as caught:
                    await mcp.list_tools("expired-token")
        self.assertEqual("AUTH_FAILED", caught.exception.code)

    async def test_timeout_has_distinct_error(self):
        def handle(_: httpx.Request) -> httpx.Response:
            raise httpx.ReadTimeout("timed out")

        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http_client:
            async with JavaMcpClient(http_client) as mcp:
                with self.assertRaises(McpClientError) as caught:
                    await mcp.list_tools("token")
        self.assertEqual("JAVA_TIMEOUT", caught.exception.code)
        self.assertTrue(caught.exception.retryable)

    async def test_rpc_error_and_mismatched_id_are_rejected(self):
        def rpc_error(request: httpx.Request) -> httpx.Response:
            request_id = json.loads(request.content)["id"]
            return httpx.Response(200, json={"jsonrpc": "2.0", "id": request_id,
                                             "error": {"code": -1, "message": "bad method"}})

        async with httpx.AsyncClient(transport=httpx.MockTransport(rpc_error)) as http_client:
            async with JavaMcpClient(http_client) as mcp:
                with self.assertRaises(McpClientError) as caught:
                    await mcp.list_tools("token")
        self.assertEqual("-1", caught.exception.code)

        transport = httpx.MockTransport(lambda _: httpx.Response(
            200, json={"jsonrpc": "2.0", "id": "wrong", "result": {"tools": []}}
        ))
        async with httpx.AsyncClient(transport=transport) as http_client:
            async with JavaMcpClient(http_client) as mcp:
                with self.assertRaises(McpClientError) as caught:
                    await mcp.list_tools("token")
        self.assertEqual("INVALID_RESPONSE", caught.exception.code)
