package com.google.ai.edge.gallery.ui.common.chat

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat {
  JSON,
  MARKDOWN,
  PLAIN_TEXT,
}

object ChatExportManager {

  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
  private val filenameDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

  fun exportToFile(
    context: Context,
    session: ChatSessionProto,
    format: ExportFormat,
  ): File {
    val content =
      when (format) {
        ExportFormat.JSON -> exportToJson(session)
        ExportFormat.MARKDOWN -> exportToMarkdown(session)
        ExportFormat.PLAIN_TEXT -> exportToPlainText(session)
      }

    val extension =
      when (format) {
        ExportFormat.JSON -> "json"
        ExportFormat.MARKDOWN -> "md"
        ExportFormat.PLAIN_TEXT -> "txt"
      }

    val timestamp = filenameDateFormat.format(Date(session.timestampMs))
    val safeTitle = session.title.take(20).replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff_\\- ]"), "").trim()
    val fileName = "chat_${safeTitle}_$timestamp.$extension"
    val file = File(context.cacheDir, fileName)
    file.writeText(content)
    return file
  }

  fun exportToString(session: ChatSessionProto, format: ExportFormat): String {
    return when (format) {
      ExportFormat.JSON -> exportToJson(session)
      ExportFormat.MARKDOWN -> exportToMarkdown(session)
      ExportFormat.PLAIN_TEXT -> exportToPlainText(session)
    }
  }

  fun shareSession(context: Context, session: ChatSessionProto, format: ExportFormat) {
    val file = exportToFile(context, session, format)
    val mimeType =
      when (format) {
        ExportFormat.JSON -> "application/json"
        ExportFormat.MARKDOWN -> "text/markdown"
        ExportFormat.PLAIN_TEXT -> "text/plain"
      }

    val uri =
      FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val shareIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Chat: ${session.title}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    context.startActivity(Intent.createChooser(shareIntent, "Share Chat"))
  }

  fun exportToJson(session: ChatSessionProto): String {
    val root = JSONObject()
    root.put("sessionId", session.sessionId)
    root.put("title", session.title)
    root.put("timestampMs", session.timestampMs)
    root.put("timestamp", dateFormat.format(Date(session.timestampMs)))
    root.put("originalModel", session.originalModel)
    root.put("taskId", session.taskId)
    root.put("messageCount", session.messagesCount)

    val messagesArray = JSONArray()
    for (msg in session.messagesList) {
      val msgObj = JSONObject()
      msgObj.put("type", msg.messageType)
      msgObj.put("side", msg.side.name)
      msgObj.put("content", msg.content)
      msgObj.put("latencyMs", msg.latencyMs.toDouble())
      msgObj.put("accelerator", msg.accelerator)

      if (msg.isMarkdown) {
        msgObj.put("isMarkdown", true)
      }
      if (msg.inProgress) {
        msgObj.put("inProgress", true)
      }
      if (msg.imageFilePathsCount > 0) {
        val images = JSONArray()
        for (path in msg.imageFilePathsList) {
          images.put(path)
        }
        msgObj.put("imageFilePaths", images)
      }
      if (msg.audioClipsCount > 0) {
        val clips = JSONArray()
        for (clip in msg.audioClipsList) {
          val clipObj = JSONObject()
          clipObj.put("filePath", clip.filePath)
          clipObj.put("sampleRate", clip.sampleRate)
          clips.put(clipObj)
        }
        msgObj.put("audioClips", clips)
      }

      messagesArray.put(msgObj)
    }
    root.put("messages", messagesArray)

    return root.toString(2)
  }

  fun exportToMarkdown(session: ChatSessionProto): String {
    val sb = StringBuilder()

    sb.appendLine("# ${session.title}")
    sb.appendLine()
    sb.appendLine("> **Model**: ${session.originalModel}")
    sb.appendLine("> **Date**: ${dateFormat.format(Date(session.timestampMs))}")
    sb.appendLine("> **Messages**: ${session.messagesCount}")
    sb.appendLine()
    sb.appendLine("---")
    sb.appendLine()

    for (msg in session.messagesList) {
      val roleLabel =
        when (msg.side) {
          ChatSideProto.CHAT_SIDE_USER -> "**You**"
          ChatSideProto.CHAT_SIDE_MODEL -> "**Model**"
          ChatSideProto.CHAT_SIDE_SYSTEM -> "**System**"
          else -> "**${msg.side.name}**"
        }

      when (msg.messageType) {
        "TEXT" -> {
          sb.appendLine("### $roleLabel")
          sb.appendLine()
          if (msg.accelerator.isNotEmpty()) {
            sb.appendLine("*Accelerator: ${msg.accelerator}*")
            sb.appendLine()
          }
          sb.appendLine(msg.content)
          sb.appendLine()
        }
        "THINKING" -> {
          sb.appendLine("### $roleLabel (Thinking)")
          sb.appendLine()
          sb.appendLine("```")
          sb.appendLine(msg.content)
          sb.appendLine("```")
          sb.appendLine()
        }
        "INFO" -> {
          sb.appendLine("> ℹ️ ${msg.content}")
          sb.appendLine()
        }
        "WARNING" -> {
          sb.appendLine("> ⚠️ ${msg.content}")
          sb.appendLine()
        }
        "ERROR" -> {
          sb.appendLine("> ❌ ${msg.content}")
          sb.appendLine()
        }
        "IMAGE" -> {
          sb.appendLine("### $roleLabel (Image)")
          if (msg.imageFilePathsCount > 0) {
            sb.appendLine()
            for (path in msg.imageFilePathsList) {
              sb.appendLine("* $path")
            }
          }
          sb.appendLine()
        }
        "AUDIO_CLIP" -> {
          sb.appendLine("### $roleLabel (Audio)")
          sb.appendLine()
          sb.appendLine("*Audio clip attached*")
          sb.appendLine()
        }
        else -> {
          if (msg.content.isNotEmpty()) {
            sb.appendLine("### $roleLabel (${msg.messageType})")
            sb.appendLine()
            sb.appendLine(msg.content)
            sb.appendLine()
          }
        }
      }

      if (msg.latencyMs > 0) {
        sb.appendLine("*Latency: ${String.format("%.1f", msg.latencyMs)}ms*")
        sb.appendLine()
      }
    }

    sb.appendLine("---")
    sb.appendLine("*Exported from Ongallery*")

    return sb.toString()
  }

  fun exportToPlainText(session: ChatSessionProto): String {
    val sb = StringBuilder()

    sb.appendLine(session.title)
    sb.appendLine("Model: ${session.originalModel}")
    sb.appendLine("Date: ${dateFormat.format(Date(session.timestampMs))}")
    sb.appendLine("Messages: ${session.messagesCount}")
    sb.appendLine(String(CharArray(60) { '=' }))
    sb.appendLine()

    for (msg in session.messagesList) {
      val roleLabel =
        when (msg.side) {
          ChatSideProto.CHAT_SIDE_USER -> "You"
          ChatSideProto.CHAT_SIDE_MODEL -> "Model"
          ChatSideProto.CHAT_SIDE_SYSTEM -> "System"
          else -> msg.side.name
        }

      when (msg.messageType) {
        "TEXT", "THINKING" -> {
          sb.appendLine("[$roleLabel]:")
          sb.appendLine(msg.content)
          sb.appendLine()
        }
        "INFO" -> {
          sb.appendLine("[INFO]: ${msg.content}")
          sb.appendLine()
        }
        "WARNING" -> {
          sb.appendLine("[WARNING]: ${msg.content}")
          sb.appendLine()
        }
        "ERROR" -> {
          sb.appendLine("[ERROR]: ${msg.content}")
          sb.appendLine()
        }
        else -> {
          if (msg.content.isNotEmpty()) {
            sb.appendLine("[$roleLabel (${msg.messageType})]:")
            sb.appendLine(msg.content)
            sb.appendLine()
          } else {
            sb.appendLine("[$roleLabel (${msg.messageType})]")
            sb.appendLine()
          }
        }
      }
    }

    sb.appendLine(String(CharArray(60) { '=' }))
    sb.appendLine("Exported from Ongallery")

    return sb.toString()
  }

  fun buildCurrentSessionProto(
    sessionId: String,
    messages: List<ChatMessage>,
    modelName: String,
    taskId: String,
  ): ChatSessionProto {
    val firstTextMessage =
      messages.filterIsInstance<ChatMessageText>().firstOrNull()?.content
    val title =
      firstTextMessage?.take(30)?.let { if (it.length == 30) "$it..." else it }
        ?: "New Chat Session"

    val protoMessages = messages.mapNotNull { msg ->
      val builder = ChatMessageProto.newBuilder()
      when (msg) {
        is ChatMessageText -> {
          builder.setMessageType("TEXT").setContent(msg.content)
            .setSide(mapChatSide(msg.side)).setLatencyMs(msg.latencyMs)
            .setAccelerator(msg.accelerator).setHideSenderLabel(msg.hideSenderLabel)
            .setIsMarkdown(msg.isMarkdown)
        }
        is ChatMessageThinking -> {
          builder.setMessageType("THINKING").setContent(msg.content)
            .setSide(mapChatSide(msg.side)).setInProgress(msg.inProgress)
            .setAccelerator(msg.accelerator).setHideSenderLabel(msg.hideSenderLabel)
        }
        is ChatMessageInfo -> {
          builder.setMessageType("INFO").setContent(msg.content)
            .setSide(mapChatSide(msg.side))
        }
        is ChatMessageWarning -> {
          builder.setMessageType("WARNING").setContent(msg.content)
            .setSide(mapChatSide(msg.side))
        }
        is ChatMessageError -> {
          builder.setMessageType("ERROR").setContent(msg.content)
            .setSide(mapChatSide(msg.side))
        }
        is ChatMessageImage -> {
          builder.setMessageType("IMAGE").setSide(mapChatSide(msg.side))
            .setLatencyMs(msg.latencyMs)
          msg.persistedPaths?.let { builder.addAllImageFilePaths(it) }
        }
        is ChatMessageAudioClip -> {
          builder.setMessageType("AUDIO_CLIP").setSide(mapChatSide(msg.side))
            .setLatencyMs(msg.latencyMs)
        }
        else -> return@mapNotNull null
      }
      builder.build()
    }

    return ChatSessionProto.newBuilder()
      .setSessionId(sessionId)
      .setTitle(title)
      .setTimestampMs(System.currentTimeMillis())
      .setOriginalModel(modelName)
      .setTaskId(taskId)
      .addAllMessages(protoMessages)
      .build()
  }

  private fun mapChatSide(side: ChatSide): ChatSideProto {
    return when (side) {
      ChatSide.USER -> ChatSideProto.CHAT_SIDE_USER
      ChatSide.AGENT -> ChatSideProto.CHAT_SIDE_MODEL
      ChatSide.SYSTEM -> ChatSideProto.CHAT_SIDE_SYSTEM
    }
  }
}