package com.sourcegraph.cody.chat

import com.sourcegraph.cody.UpdatableChat
import com.sourcegraph.cody.agent.CodyAgent
import com.sourcegraph.cody.agent.CodyAgentClient
import com.sourcegraph.cody.agent.CodyAgentServer
import com.sourcegraph.cody.agent.protocol.ContextFile
import com.sourcegraph.cody.agent.protocol.ExecuteRecipeParams
import com.sourcegraph.cody.api.Speaker
import com.sourcegraph.cody.context.ContextMessage
import com.sourcegraph.cody.vscode.CancellationToken
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.stream.Collectors

class Chat {
  @Throws(ExecutionException::class, InterruptedException::class)
  fun sendMessageViaAgent(
      client: CodyAgentClient,
      codyAgentServer: CompletableFuture<CodyAgentServer?>,
      humanMessage: ChatMessage,
      recipeId: String,
      chat: UpdatableChat,
      token: CancellationToken
  ) {
    val isFirstMessage = AtomicBoolean(false)
    client.onFinishedProcessing = Runnable { chat.finishMessageProcessing() }
    client.onChatUpdateMessageInProgress = Consumer { agentChatMessage ->
      val agentChatMessageText = agentChatMessage.text ?: return@Consumer
      val chatMessage =
          ChatMessage(Speaker.ASSISTANT, agentChatMessageText, agentChatMessage.displayText)
      if (isFirstMessage.compareAndSet(false, true)) {
        val contextMessages =
            agentChatMessage
                .actualContextFiles()
                .stream()
                .map { contextFile: ContextFile ->
                  ContextMessage(
                      Speaker.ASSISTANT,
                      agentChatMessageText,
                      com.sourcegraph.cody.context.ContextFile(
                          contextFile.fileName, contextFile.repoName, contextFile.revision))
                }
                .collect(Collectors.toList())
        chat.displayUsedContext(contextMessages)
        chat.addMessageToChat(chatMessage)
      } else {
        chat.updateLastMessage(chatMessage)
      }
    }
    codyAgentServer
        .thenAcceptAsync(
            { server: CodyAgentServer? ->
              try {
                val recipesExecuteFuture =
                    server!!.recipesExecute(
                        ExecuteRecipeParams()
                            .setId(recipeId)
                            .setHumanChatInput(humanMessage.actualMessage()))
                token.onCancellationRequested { recipesExecuteFuture.cancel(true) }
              } catch (ignored: Exception) {
                // Ignore bugs in the agent when executing recipes
              }
            },
            CodyAgent.executorService)
        .get()
  }
}