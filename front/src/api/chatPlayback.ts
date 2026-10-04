import { createStreamTextPresenter } from '../utils/streamText.ts';
import { createAgentStepPresenter } from './agentTimeline.ts';
import { consumeChatStream } from './chatStream.ts';
import type { ChatStreamHandlers } from './chatStream.ts';

/** 先播完本轮过程步骤，再按原有节奏展示回复正文。 */
export async function playChatStream(
    stream: ReadableStream<Uint8Array>, handlers: ChatStreamHandlers,
): Promise<void> {
    const textPresenter = createStreamTextPresenter(handlers.onToken);
    const stepPresenter = handlers.onAgentStep
        ? createAgentStepPresenter(handlers.onAgentStep) : undefined;
    let bufferedText = '';
    try {
        await consumeChatStream(stream, {
            ...handlers,
            onToken: token => {
                if (stepPresenter) bufferedText += token;
                else textPresenter.append(token);
            },
            onAgentStep: stepPresenter?.append,
            onDone: () => {},
        });
        await stepPresenter?.finish();
        if (stepPresenter) textPresenter.append(bufferedText);
        await textPresenter.finish();
        handlers.onDone();
    } finally {
        textPresenter.cancel();
        stepPresenter?.cancel();
    }
}
