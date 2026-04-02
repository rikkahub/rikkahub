package me.rerere.rikkahub.ui.components.richtext

import java.math.BigDecimal

private val HTML_ROOT_TAG_REGEX = Regex("""^<\s*html\b""", RegexOption.IGNORE_CASE)
private val HTML_OPEN_TAG_REGEX = Regex("""<\s*html\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HEAD_OPEN_TAG_REGEX = Regex("""<\s*head\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HEAD_CLOSE_TAG_REGEX = Regex("""</\s*head\s*>""", RegexOption.IGNORE_CASE)
private val VIEWPORT_META_REGEX =
    Regex("""<meta\b[^>]*name\s*=\s*(["'])viewport\1[^>]*>""", RegexOption.IGNORE_CASE)
private val XML_LEADING_MISC_REGEX =
    Regex("""^\uFEFF?(?:\s|<\?xml[\s\S]*?\?>|<!--[\s\S]*?-->|<!DOCTYPE[\s\S]*?>|<\?[\s\S]*?\?>)*""", RegexOption.IGNORE_CASE)
private val XML_ROOT_TAG_REGEX = Regex("""^<\s*([A-Za-z_][A-Za-z0-9_.:-]*)\b""")
private val CSS_MIN_HEIGHT_REGEX =
    Regex("""(min-height\s*:\s*)([^;{}]*?\d+(?:\.\d+)?vh)(?=\s*[;}])""", RegexOption.IGNORE_CASE)
private val INLINE_MIN_HEIGHT_REGEX =
    Regex("""(min-height\s*:\s*)([^;]*?\d+(?:\.\d+)?vh)""", RegexOption.IGNORE_CASE)
private val INLINE_STYLE_REGEX = Regex("""(style\s*=\s*(["']))([\s\S]*?)(\2)""", RegexOption.IGNORE_CASE)
private val JS_MIN_HEIGHT_ASSIGNMENT_REGEX =
    Regex("""(\.style\.minHeight\s*=\s*(["']))([\s\S]*?)(\2)""", RegexOption.IGNORE_CASE)
private val JS_SET_PROPERTY_MIN_HEIGHT_REGEX =
    Regex(
        """(setProperty\s*\(\s*(["'])min-height\2\s*,\s*(["']))([\s\S]*?)(\3\s*\))""",
        RegexOption.IGNORE_CASE
    )
private val VH_VALUE_REGEX = Regex("""(\d+(?:\.\d+)?)vh\b""", RegexOption.IGNORE_CASE)

internal const val CODE_BLOCK_HEIGHT_BRIDGE_NAME = "RikkaHubCodeBlockBridge"
internal const val CODE_BLOCK_ACTION_BRIDGE_NAME = "RikkaHubChatActionBridge"

internal enum class CodeBlockRenderScrollMode {
    AUTO_HEIGHT,
    SCROLLABLE,
}

internal enum class CodeBlockRenderType {
    HTML,
    SVG,
}

internal data class CodeBlockRenderTarget(
    val normalizedLanguage: String,
    val renderType: CodeBlockRenderType,
)

internal object CodeBlockRenderResolver {
    fun resolve(
        language: String,
        code: String,
    ): CodeBlockRenderTarget? {
        val normalized = normalizeLanguage(language)
        return when (normalized) {
            "html" -> CodeBlockRenderTarget(normalizedLanguage = normalized, renderType = CodeBlockRenderType.HTML)
            "svg" -> CodeBlockRenderTarget(normalizedLanguage = normalized, renderType = CodeBlockRenderType.SVG)
            "xml" -> {
                if (containsSvgMarkup(code)) {
                    CodeBlockRenderTarget(normalizedLanguage = normalized, renderType = CodeBlockRenderType.SVG)
                } else {
                    null
                }
            }

            else -> null
        }
    }

    fun buildHtmlForWebView(
        target: CodeBlockRenderTarget,
        code: String,
        backgroundColor: String = "#ffffff",
        textColor: String = "#000000",
        scrollMode: CodeBlockRenderScrollMode = CodeBlockRenderScrollMode.AUTO_HEIGHT,
    ): String {
        return when (target.renderType) {
            CodeBlockRenderType.HTML -> buildHtmlDocument(
                content = replaceVhInContent(code),
                backgroundColor = backgroundColor,
                textColor = textColor,
                scrollMode = scrollMode,
            )

            CodeBlockRenderType.SVG -> createRenderShell(
                content = replaceVhInContent(code),
                backgroundColor = backgroundColor,
                textColor = textColor,
                scrollMode = scrollMode,
            )
        }
    }

    private fun normalizeLanguage(language: String): String {
        if (language.isBlank()) return ""
        val firstToken = language.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .firstOrNull()
            .orEmpty()
            .takeWhile { ch ->
                ch.isLetterOrDigit() || ch == '+' || ch == '-' || ch == '_' || ch == '.' || ch == '/'
            }
        return when (firstToken) {
            "htm", "xhtml", "text/html", "application/xhtml+xml" -> "html"
            "image/svg+xml" -> "svg"
            "application/xml", "text/xml" -> "xml"
            else -> firstToken
        }
    }

    private fun containsSvgMarkup(code: String): Boolean {
        return extractXmlRootTagName(code) == "svg"
    }

    private fun replaceVhInContent(content: String): String {
        var updated = content

        // 1) CSS declarations: min-height: 100vh;
        updated = updated.replace(CSS_MIN_HEIGHT_REGEX) { match ->
            val prefix = match.groupValues[1]
            val value = match.groupValues[2]
            "$prefix${convertVhToViewportVariable(value)}"
        }

        // 2) Inline style attribute: style="min-height: 80vh"
        updated = updated.replace(INLINE_STYLE_REGEX) { match ->
            val styleContent = match.groupValues[3]
            if (!styleContent.contains("min-height", ignoreCase = true) || !styleContent.contains("vh", ignoreCase = true)) {
                return@replace match.value
            }
            val replacedStyle = styleContent.replace(INLINE_MIN_HEIGHT_REGEX) { styleMatch ->
                val stylePrefix = styleMatch.groupValues[1]
                val styleValue = styleMatch.groupValues[2]
                "$stylePrefix${convertVhToViewportVariable(styleValue)}"
            }
            "${match.groupValues[1]}$replacedStyle${match.groupValues[4]}"
        }

        // 3) JavaScript assignment: element.style.minHeight = "100vh"
        updated = updated.replace(JS_MIN_HEIGHT_ASSIGNMENT_REGEX) { match ->
            val value = match.groupValues[3]
            if (!VH_VALUE_REGEX.containsMatchIn(value)) {
                return@replace match.value
            }
            "${match.groupValues[1]}${convertVhToViewportVariable(value)}${match.groupValues[4]}"
        }

        // 4) JavaScript setProperty: style.setProperty('min-height', '100vh')
        updated = updated.replace(JS_SET_PROPERTY_MIN_HEIGHT_REGEX) { match ->
            val value = match.groupValues[4]
            if (!VH_VALUE_REGEX.containsMatchIn(value)) {
                return@replace match.value
            }
            "${match.groupValues[1]}${convertVhToViewportVariable(value)}${match.groupValues[5]}"
        }

        return updated
    }

    private fun convertVhToViewportVariable(value: String): String {
        return VH_VALUE_REGEX.replace(value) { match ->
            val raw = match.groupValues[1]
            val parsed = raw.toDoubleOrNull() ?: return@replace match.value
            if (!parsed.isFinite()) return@replace match.value
            if (parsed == 100.0) {
                "var(--TH-viewport-height)"
            } else {
                val ratio = (parsed / 100.0).toPlainString()
                "calc(var(--TH-viewport-height) * $ratio)"
            }
        }
    }

    private fun Double.toPlainString(): String {
        return BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
    }

    private fun buildHtmlDocument(
        content: String,
        backgroundColor: String,
        textColor: String,
        scrollMode: CodeBlockRenderScrollMode,
    ): String {
        return if (looksLikeFullHtmlDocument(content)) {
            injectRenderSupportIntoHtmlDocument(content, scrollMode)
        } else {
            createRenderShell(
                content = content,
                backgroundColor = backgroundColor,
                textColor = textColor,
                scrollMode = scrollMode,
            )
        }
    }

    private fun looksLikeFullHtmlDocument(content: String): Boolean {
        val leadingMisc = XML_LEADING_MISC_REGEX.find(content)?.value.orEmpty()
        return HTML_ROOT_TAG_REGEX.containsMatchIn(content.substring(leadingMisc.length))
    }

    private fun injectRenderSupportIntoHtmlDocument(
        content: String,
        scrollMode: CodeBlockRenderScrollMode,
    ): String {
        val headInjection = buildHeadInjection(
            backgroundColor = null,
            textColor = null,
            scrollMode = scrollMode,
            preserveDocumentStyles = true,
            includeViewportMeta = !VIEWPORT_META_REGEX.containsMatchIn(content),
        )

        val headCloseTag = HEAD_CLOSE_TAG_REGEX.find(content)
        if (headCloseTag != null) {
            return insertBefore(content, headCloseTag.range.first, headInjection)
        }

        val headOpenTag = HEAD_OPEN_TAG_REGEX.find(content)
        if (headOpenTag != null) {
            return insertAfter(content, headOpenTag.range.last + 1, headInjection)
        }

        val htmlOpenTag = HTML_OPEN_TAG_REGEX.find(content)
        if (htmlOpenTag != null) {
            return insertAfter(content, htmlOpenTag.range.last + 1, "<head>$headInjection</head>")
        }

        return createRenderShell(
            content = content,
            backgroundColor = "#ffffff",
            textColor = "#000000",
            scrollMode = scrollMode,
        )
    }

    private fun createRenderShell(
        content: String,
        backgroundColor: String = "#ffffff",
        textColor: String = "#000000",
        scrollMode: CodeBlockRenderScrollMode,
    ): String {
        val headInjection = buildHeadInjection(
            backgroundColor = backgroundColor,
            textColor = textColor,
            scrollMode = scrollMode,
            preserveDocumentStyles = false,
            includeViewportMeta = true,
        )
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            $headInjection
            </head>
            <body>
            $content
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildHeadInjection(
        backgroundColor: String?,
        textColor: String?,
        scrollMode: CodeBlockRenderScrollMode,
        preserveDocumentStyles: Boolean,
        includeViewportMeta: Boolean,
    ): String {
        val style = if (preserveDocumentStyles) {
            createExistingDocumentStyle(scrollMode)
        } else {
            createFragmentShellStyle(
                backgroundColor = backgroundColor ?: "#ffffff",
                textColor = textColor ?: "#000000",
                scrollMode = scrollMode,
            )
        }

        val viewportMeta = if (includeViewportMeta) {
            """<meta name="viewport" content="width=device-width, initial-scale=1.0">"""
        } else {
            ""
        }

        return buildString {
            append(viewportMeta)
            append(style)
            append(createBridgeScript())
        }
    }

    private fun createFragmentShellStyle(
        backgroundColor: String,
        textColor: String,
        scrollMode: CodeBlockRenderScrollMode,
    ): String {
        val overflowY = when (scrollMode) {
            CodeBlockRenderScrollMode.AUTO_HEIGHT -> "hidden"
            CodeBlockRenderScrollMode.SCROLLABLE -> "auto"
        }
        return """
            <style>
            :root{--TH-viewport-height:100vh;}
            *,*::before,*::after{box-sizing:border-box;}
            html,body{
              margin:0!important;
              padding:0;
              overflow-x:hidden!important;
              overflow-y:$overflowY!important;
              max-width:100%!important;
              background-color:$backgroundColor;
              color:$textColor;
            }
            </style>
        """.trimIndent()
    }

    private fun createExistingDocumentStyle(scrollMode: CodeBlockRenderScrollMode): String {
        val overflowRule = when (scrollMode) {
            CodeBlockRenderScrollMode.AUTO_HEIGHT -> "overflow-x:hidden!important;"
            CodeBlockRenderScrollMode.SCROLLABLE -> "overflow-x:hidden!important;overflow-y:auto!important;"
        }
        return """
            <style>
            :root{--TH-viewport-height:100vh;}
            html,body{$overflowRule}
            </style>
        """.trimIndent()
    }

    private fun createBridgeScript(): String {
        return """
            <script>
            (function() {
              function getActionBridge() {
                return window.$CODE_BLOCK_ACTION_BRIDGE_NAME;
              }

              function getDraftText() {
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.getDraftText === 'function') {
                    return String(bridge.getDraftText() || '');
                  }
                } catch (_err) {}
                return window.__RH_CHAT_DRAFT__ || '';
              }

              function setDraftText(value) {
                var nextValue = String(value || '');
                window.__RH_CHAT_DRAFT__ = nextValue;
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.setDraftText === 'function') {
                    bridge.setDraftText(nextValue);
                  }
                } catch (_err) {}
                return nextValue;
              }

              function appendDraftText(value) {
                var text = String(value || '');
                if (!text) return getDraftText();
                var nextValue = getDraftText() + text;
                window.__RH_CHAT_DRAFT__ = nextValue;
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.appendDraftText === 'function') {
                    bridge.appendDraftText(text);
                    return nextValue;
                  }
                } catch (_err) {}
                return setDraftText(nextValue);
              }

              function sendCurrentDraft(answer) {
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.sendCurrentDraft === 'function') {
                    bridge.sendCurrentDraft(answer !== false);
                  }
                } catch (_err) {}
              }

              function sendTextNow(text, answer) {
                var nextValue = String(text || '');
                window.__RH_CHAT_DRAFT__ = nextValue;
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.sendText === 'function') {
                    bridge.sendText(nextValue, answer !== false);
                    return;
                  }
                } catch (_err) {}
                setDraftText(nextValue);
                sendCurrentDraft(answer);
              }

              function getHistorySnapshotRaw() {
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.getHistorySnapshot === 'function') {
                    return String(bridge.getHistorySnapshot() || '');
                  }
                } catch (_err) {}
                return window.__RH_HISTORY_SNAPSHOT_RAW__ || '';
              }

              function normalizeHistoryMessage(message, index, userName, assistantName) {
                var role = String(message && message.role || '').toLowerCase();
                var swipes = Array.isArray(message && message.swipes)
                  ? message.swipes.map(function(item) { return String(item || ''); })
                  : [];
                var swipeIndex = Number(message && message.swipeIndex);
                if (!Number.isFinite(swipeIndex) || swipeIndex < 0) {
                  swipeIndex = 0;
                }
                var fallbackText = String(message && message.text || '');
                if (!swipes.length) {
                  swipes = [fallbackText];
                }
                if (swipeIndex >= swipes.length) {
                  swipeIndex = swipes.length - 1;
                }
                if (swipeIndex < 0) {
                  swipeIndex = 0;
                }
                var text = String(message && message.text != null ? message.text : swipes[swipeIndex] || '');
                if (!text && swipes.length) {
                  text = swipes[swipeIndex] || '';
                }
                var name = String(message && message.name || '');
                if (!name) {
                  if (role === 'assistant') {
                    name = assistantName;
                  } else if (role === 'user') {
                    name = userName;
                  } else if (role === 'system') {
                    name = 'System';
                  } else {
                    name = 'Tool';
                  }
                }
                return {
                  index: index,
                  nodeId: String(message && message.nodeId || ''),
                  messageId: String(message && message.messageId || ''),
                  role: role || 'user',
                  name: name,
                  text: text,
                  swipeIndex: swipeIndex,
                  swipes: swipes.slice(),
                };
              }

              function reindexHistoryMessages(messages) {
                messages.forEach(function(message, index) {
                  message.index = index;
                });
                return messages;
              }

              function loadHistorySnapshot(force) {
                if (!force && window.__RH_HISTORY_SNAPSHOT__) {
                  return window.__RH_HISTORY_SNAPSHOT__;
                }
                var parsed = null;
                try {
                  parsed = JSON.parse(getHistorySnapshotRaw() || '{}');
                } catch (_err) {}
                if (!parsed || typeof parsed !== 'object') {
                  parsed = {};
                }
                var userName = String(parsed.userName || 'User');
                var assistantName = String(parsed.assistantName || 'Assistant');
                var rawMessages = Array.isArray(parsed.messages) ? parsed.messages : [];
                var normalizedMessages = rawMessages.map(function(message, index) {
                  return normalizeHistoryMessage(message, index, userName, assistantName);
                });
                var snapshot = {
                  conversationId: String(parsed.conversationId || ''),
                  userName: userName,
                  assistantName: assistantName,
                  messages: reindexHistoryMessages(normalizedMessages),
                };
                window.__RH_HISTORY_SNAPSHOT__ = snapshot;
                if (!window.__RH_HISTORY_EDIT_BUFFERS__) {
                  window.__RH_HISTORY_EDIT_BUFFERS__ = {};
                }
                return snapshot;
              }

              function findHistoryMessageByNodeId(nodeId) {
                var key = String(nodeId || '');
                if (!key) return null;
                var messages = loadHistorySnapshot(false).messages;
                for (var i = 0; i < messages.length; i++) {
                  if (messages[i].nodeId === key) {
                    return messages[i];
                  }
                }
                return null;
              }

              function findHistoryMessageByMesId(mesId) {
                var index = Number(mesId);
                if (!Number.isFinite(index)) return null;
                var messages = loadHistorySnapshot(false).messages;
                return messages[index] || null;
              }

              function getHistoryEditBuffers() {
                if (!window.__RH_HISTORY_EDIT_BUFFERS__) {
                  window.__RH_HISTORY_EDIT_BUFFERS__ = {};
                }
                return window.__RH_HISTORY_EDIT_BUFFERS__;
              }

              function isHistoryEditing(entry) {
                if (!entry || !entry.nodeId) return false;
                return !!getHistoryEditBuffers()[entry.nodeId];
              }

              function beginHistoryEdit(entry) {
                if (!entry || !entry.nodeId) return null;
                getHistoryEditBuffers()[entry.nodeId] = {
                  text: entry.text,
                };
                return entry;
              }

              function clearHistoryEdit(entry) {
                if (!entry || !entry.nodeId) return;
                delete getHistoryEditBuffers()[entry.nodeId];
              }

              function getHistoryEditableText(entry) {
                if (!entry) return '';
                var buffer = entry.nodeId ? getHistoryEditBuffers()[entry.nodeId] : null;
                return String(buffer && buffer.text != null ? buffer.text : entry.text || '');
              }

              function setHistoryEditableText(entry, value) {
                if (!entry || !entry.nodeId) return '';
                getHistoryEditBuffers()[entry.nodeId] = {
                  text: String(value == null ? '' : value),
                };
                return getHistoryEditableText(entry);
              }

              function editHistoryMessage(nodeId, text) {
                var entry = findHistoryMessageByNodeId(nodeId);
                if (!entry) return false;
                var nextText = String(text == null ? '' : text);
                entry.text = nextText;
                if (!Array.isArray(entry.swipes) || !entry.swipes.length) {
                  entry.swipes = [nextText];
                }
                entry.swipes[entry.swipeIndex] = nextText;
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.editHistoryMessage === 'function') {
                    bridge.editHistoryMessage(entry.nodeId, nextText);
                  }
                } catch (_err) {}
                syncCompatContextFromHistory();
                return true;
              }

              function commitHistoryEdit(entry) {
                if (!entry) return false;
                var nextText = getHistoryEditableText(entry);
                clearHistoryEdit(entry);
                return editHistoryMessage(entry.nodeId, nextText);
              }

              function deleteHistoryMessage(nodeId) {
                var state = loadHistorySnapshot(false);
                var nextMessages = state.messages.filter(function(message) {
                  return message.nodeId !== String(nodeId || '');
                });
                if (nextMessages.length === state.messages.length) {
                  return false;
                }
                state.messages = reindexHistoryMessages(nextMessages);
                clearHistoryEdit({ nodeId: String(nodeId || '') });
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.deleteHistoryMessage === 'function') {
                    bridge.deleteHistoryMessage(String(nodeId || ''));
                  }
                } catch (_err) {}
                syncCompatContextFromHistory();
                return true;
              }

              function selectHistoryMessageNode(nodeId, selectIndex) {
                var entry = findHistoryMessageByNodeId(nodeId);
                if (!entry) return false;
                var nextIndex = Number(selectIndex);
                if (!Number.isFinite(nextIndex)) return false;
                nextIndex = Math.max(0, Math.min(entry.swipes.length - 1, nextIndex));
                entry.swipeIndex = nextIndex;
                entry.text = entry.swipes[nextIndex] || '';
                clearHistoryEdit(entry);
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.selectHistoryMessageNode === 'function') {
                    bridge.selectHistoryMessageNode(entry.nodeId, nextIndex);
                  }
                } catch (_err) {}
                syncCompatContextFromHistory();
                return true;
              }

              function regenerateHistoryMessage(nodeId, regenerateAssistantMessage) {
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.regenerateHistoryMessage === 'function') {
                    bridge.regenerateHistoryMessage(
                      String(nodeId || ''),
                      regenerateAssistantMessage !== false
                    );
                    return true;
                  }
                } catch (_err) {}
                return false;
              }

              function continueHistoryMessage(nodeId) {
                try {
                  var bridge = getActionBridge();
                  if (bridge && typeof bridge.continueHistoryMessage === 'function') {
                    bridge.continueHistoryMessage(String(nodeId || ''));
                    return true;
                  }
                } catch (_err) {}
                return false;
              }

              function stripHtml(value) {
                return String(value || '')
                  .replace(/<br\s*\/?>/gi, '\n')
                  .replace(/<[^>]+>/g, '');
              }

              function escapeHtml(value) {
                return String(value || '')
                  .replace(/&/g, '&amp;')
                  .replace(/</g, '&lt;')
                  .replace(/>/g, '&gt;')
                  .replace(/"/g, '&quot;')
                  .replace(/'/g, '&#39;');
              }

              function toNodeList(items) {
                var list = (Array.isArray(items) ? items : []).filter(Boolean);
                list.item = function(index) {
                  return list[index] || null;
                };
                return list;
              }

              function getHistoryClasses(entry, kind) {
                var classes = [];
                if (kind === 'chat') {
                  classes.push('chat');
                  return classes;
                }
                if (kind === 'mes') {
                  classes.push('mes');
                  classes.push(entry.role + '_mes');
                  classes.push('mes_' + entry.role);
                  if (entry.index === loadHistorySnapshot(false).messages.length - 1) {
                    classes.push('last_mes');
                  }
                  if (isHistoryEditing(entry)) {
                    classes.push('editing');
                  }
                  return classes;
                }
                if (kind === 'mes_text') return ['mes_text'];
                if (kind === 'mes_edit') return ['mes_edit'];
                if (kind === 'mes_edit_done') return ['mes_edit_done'];
                return classes;
              }

              function createClassList(entry, kind) {
                return {
                  contains: function(name) {
                    return getHistoryClasses(entry, kind).indexOf(String(name || '')) >= 0;
                  },
                  toString: function() {
                    return getHistoryClasses(entry, kind).join(' ');
                  },
                };
              }

              function createHistoryDataset(entry) {
                return {
                  mesid: String(entry.index),
                  messageId: entry.messageId,
                  nodeId: entry.nodeId,
                  swipeId: String(entry.swipeIndex),
                };
              }

              function getHistoryAttribute(entry, name) {
                var key = String(name || '').toLowerCase();
                if (key === 'mesid' || key === 'data-mesid') return String(entry.index);
                if (key === 'messageid' || key === 'data-message-id') return entry.messageId;
                if (key === 'nodeid' || key === 'data-node-id') return entry.nodeId;
                if (key === 'swipe_id' || key === 'data-swipe-id') return String(entry.swipeIndex);
                if (key === 'is_user' || key === 'data-is-user') return entry.role === 'user' ? 'true' : 'false';
                if (key === 'is_system' || key === 'data-is-system') return entry.role === 'system' ? 'true' : 'false';
                return null;
              }

              function setHistoryAttribute(entry, name, value) {
                var key = String(name || '').toLowerCase();
                if (key === 'swipe_id' || key === 'data-swipe-id') {
                  selectHistoryMessageNode(entry.nodeId, Number(value));
                }
              }

              function isHistorySelector(selector) {
                var raw = String(selector || '').trim();
                if (!raw) return false;
                return (
                  raw === '#chat' ||
                  raw.indexOf('.mes') >= 0 ||
                  raw.indexOf('last_mes') >= 0 ||
                  raw.indexOf('mes_text') >= 0 ||
                  raw.indexOf('mes_edit_done') >= 0 ||
                  raw.indexOf('mes_edit') >= 0 ||
                  raw.indexOf('mesid') >= 0 ||
                  raw.indexOf('data-message-id') >= 0 ||
                  raw.indexOf('data-node-id') >= 0 ||
                  raw.indexOf('textarea') >= 0
                );
              }

              function createHistoryTextNode(entry) {
                return {
                  __rhType: 'mes_text',
                  __rhNodeId: entry.nodeId,
                  nodeType: 1,
                  tagName: 'TEXTAREA',
                  contentEditable: true,
                  classList: createClassList(entry, 'mes_text'),
                  dataset: createHistoryDataset(entry),
                  focus: function() {},
                  blur: function() {},
                  click: function() {
                    beginHistoryEdit(entry);
                    return true;
                  },
                  dispatchEvent: function(event) {
                    if (event && event.type === 'input') {
                      setHistoryEditableText(entry, this.value);
                    }
                    return true;
                  },
                  getAttribute: function(name) {
                    return getHistoryAttribute(entry, name);
                  },
                  setAttribute: function(name, value) {
                    setHistoryAttribute(entry, name, value);
                  },
                  hasAttribute: function(name) {
                    return this.getAttribute(name) !== null;
                  },
                  querySelector: function() { return null; },
                  querySelectorAll: function() { return toNodeList([]); },
                  closest: function(selector) {
                    if (String(selector || '').indexOf('mes_text') >= 0 || String(selector || '').indexOf('textarea') >= 0) {
                      return this;
                    }
                    return createHistoryMessageNode(entry);
                  },
                  get value() {
                    return getHistoryEditableText(entry);
                  },
                  set value(nextValue) {
                    setHistoryEditableText(entry, nextValue);
                  },
                  get textContent() {
                    return getHistoryEditableText(entry);
                  },
                  set textContent(nextValue) {
                    setHistoryEditableText(entry, nextValue);
                  },
                  get innerText() {
                    return getHistoryEditableText(entry);
                  },
                  set innerText(nextValue) {
                    setHistoryEditableText(entry, nextValue);
                  },
                  get innerHTML() {
                    return escapeHtml(getHistoryEditableText(entry)).replace(/\n/g, '<br>');
                  },
                  set innerHTML(nextValue) {
                    setHistoryEditableText(entry, stripHtml(nextValue));
                  },
                };
              }

              function createHistoryEditButton(entry) {
                return {
                  __rhType: 'mes_edit',
                  __rhNodeId: entry.nodeId,
                  nodeType: 1,
                  tagName: 'BUTTON',
                  classList: createClassList(entry, 'mes_edit'),
                  dataset: createHistoryDataset(entry),
                  click: function() {
                    beginHistoryEdit(entry);
                    return true;
                  },
                  dispatchEvent: function(event) {
                    if (event && event.type === 'click') {
                      beginHistoryEdit(entry);
                    }
                    return true;
                  },
                  querySelector: function() { return null; },
                  querySelectorAll: function() { return toNodeList([]); },
                  closest: function() {
                    return createHistoryMessageNode(entry);
                  },
                  getAttribute: function(name) {
                    return getHistoryAttribute(entry, name);
                  },
                  setAttribute: function(name, value) {
                    setHistoryAttribute(entry, name, value);
                  },
                  hasAttribute: function(name) {
                    return this.getAttribute(name) !== null;
                  },
                };
              }

              function createHistoryEditDoneButton(entry) {
                return {
                  __rhType: 'mes_edit_done',
                  __rhNodeId: entry.nodeId,
                  nodeType: 1,
                  tagName: 'BUTTON',
                  classList: createClassList(entry, 'mes_edit_done'),
                  dataset: createHistoryDataset(entry),
                  click: function() {
                    commitHistoryEdit(entry);
                    return true;
                  },
                  dispatchEvent: function(event) {
                    if (event && event.type === 'click') {
                      commitHistoryEdit(entry);
                    }
                    return true;
                  },
                  querySelector: function() { return null; },
                  querySelectorAll: function() { return toNodeList([]); },
                  closest: function() {
                    return createHistoryMessageNode(entry);
                  },
                  getAttribute: function(name) {
                    return getHistoryAttribute(entry, name);
                  },
                  setAttribute: function(name, value) {
                    setHistoryAttribute(entry, name, value);
                  },
                  hasAttribute: function(name) {
                    return this.getAttribute(name) !== null;
                  },
                };
              }

              function createHistoryMessageNode(entry) {
                return {
                  __rhType: 'mes',
                  __rhNodeId: entry.nodeId,
                  nodeType: 1,
                  tagName: 'DIV',
                  classList: createClassList(entry, 'mes'),
                  dataset: createHistoryDataset(entry),
                  click: function() {
                    return true;
                  },
                  dispatchEvent: function() {
                    return true;
                  },
                  getAttribute: function(name) {
                    return getHistoryAttribute(entry, name);
                  },
                  setAttribute: function(name, value) {
                    setHistoryAttribute(entry, name, value);
                  },
                  hasAttribute: function(name) {
                    return this.getAttribute(name) !== null;
                  },
                  querySelector: function(selector) {
                    return queryHistorySelectorAll(selector, entry).item(0);
                  },
                  querySelectorAll: function(selector) {
                    return queryHistorySelectorAll(selector, entry);
                  },
                  closest: function(selector) {
                    if (String(selector || '').indexOf('mes') >= 0) {
                      return this;
                    }
                    return null;
                  },
                  get textContent() {
                    return getHistoryEditableText(entry);
                  },
                  set textContent(nextValue) {
                    setHistoryEditableText(entry, nextValue);
                  },
                  get innerText() {
                    return getHistoryEditableText(entry);
                  },
                  set innerText(nextValue) {
                    setHistoryEditableText(entry, nextValue);
                  },
                  get innerHTML() {
                    return escapeHtml(getHistoryEditableText(entry)).replace(/\n/g, '<br>');
                  },
                  set innerHTML(nextValue) {
                    setHistoryEditableText(entry, stripHtml(nextValue));
                  },
                };
              }

              function createHistoryChatNode() {
                return {
                  __rhType: 'chat',
                  nodeType: 1,
                  tagName: 'DIV',
                  classList: createClassList(null, 'chat'),
                  dataset: {},
                  querySelector: function(selector) {
                    return queryHistorySelectorAll(selector).item(0);
                  },
                  querySelectorAll: function(selector) {
                    return queryHistorySelectorAll(selector);
                  },
                  getAttribute: function(name) {
                    return String(name || '').toLowerCase() === 'id' ? 'chat' : null;
                  },
                  hasAttribute: function(name) {
                    return String(name || '').toLowerCase() === 'id';
                  },
                };
              }

              function queryHistorySelectorAll(selector, scopeEntry) {
                var raw = String(selector || '').trim();
                if (!raw) return toNodeList([]);
                var normalized = raw.replace(/:last(?!-child)/g, ':last-child');
                var targetScope = scopeEntry ? [scopeEntry] : loadHistorySnapshot(false).messages.slice();

                if (normalized === '#chat') {
                  return toNodeList([createHistoryChatNode()]);
                }

                var mesIdMatch = normalized.match(/\[(?:data-)?mesid\s*=\s*["']?(\d+)["']?\]/i);
                if (mesIdMatch) {
                  var scoped = findHistoryMessageByMesId(Number(mesIdMatch[1]));
                  targetScope = scoped ? [scoped] : [];
                }

                var nodeIdMatch = normalized.match(/\[data-node-id\s*=\s*["']?([^"' \]]+)["']?\]/i);
                if (nodeIdMatch) {
                  var scopedByNode = findHistoryMessageByNodeId(nodeIdMatch[1]);
                  targetScope = scopedByNode ? [scopedByNode] : [];
                }

                if (normalized.indexOf('.assistant_mes') >= 0 || normalized.indexOf('.mes_assistant') >= 0) {
                  targetScope = targetScope.filter(function(entry) { return entry.role === 'assistant'; });
                }
                if (normalized.indexOf('.user_mes') >= 0 || normalized.indexOf('.mes_user') >= 0) {
                  targetScope = targetScope.filter(function(entry) { return entry.role === 'user'; });
                }
                if (normalized.indexOf('.system_mes') >= 0 || normalized.indexOf('.mes_system') >= 0) {
                  targetScope = targetScope.filter(function(entry) { return entry.role === 'system'; });
                }
                if (normalized.indexOf('.last_mes') >= 0 || normalized.indexOf(':last-child') >= 0) {
                  targetScope = targetScope.length ? [targetScope[targetScope.length - 1]] : [];
                }

                if (normalized.indexOf('.mes_edit_done') >= 0) {
                  return toNodeList(targetScope.map(createHistoryEditDoneButton));
                }
                if (normalized.indexOf('.mes_edit') >= 0) {
                  return toNodeList(targetScope.map(createHistoryEditButton));
                }
                if (normalized.indexOf('.mes_text') >= 0 || normalized.indexOf('textarea') >= 0) {
                  return toNodeList(targetScope.map(createHistoryTextNode));
                }
                if (
                  normalized.indexOf('.mes') >= 0 ||
                  mesIdMatch ||
                  nodeIdMatch ||
                  normalized.indexOf('last_mes') >= 0
                ) {
                  return toNodeList(targetScope.map(createHistoryMessageNode));
                }
                return toNodeList([]);
              }

              function createCompatChatEntry(entry) {
                return {
                  __nodeId: entry.nodeId,
                  __messageId: entry.messageId,
                  name: entry.name,
                  is_user: entry.role === 'user',
                  is_system: entry.role === 'system',
                  mes: entry.text,
                  extra: {},
                  swipes: Array.isArray(entry.swipes) ? entry.swipes.slice() : [entry.text],
                  swipe_id: Number(entry.swipeIndex || 0),
                };
              }

              function syncCompatContextFromHistory() {
                if (!window.__RH_ST_CONTEXT__) return;
                var snapshot = loadHistorySnapshot(false);
                window.__RH_ST_CONTEXT__.__conversationId = snapshot.conversationId;
                window.__RH_ST_CONTEXT__.name1 = snapshot.userName;
                window.__RH_ST_CONTEXT__.name2 = snapshot.assistantName;
                window.__RH_ST_CONTEXT__.userName = snapshot.userName;
                window.__RH_ST_CONTEXT__.charName = snapshot.assistantName;
                window.__RH_ST_CONTEXT__.chat = snapshot.messages.map(createCompatChatEntry);
                window.__RH_ST_CONTEXT__.__sourceChat = window.__RH_ST_CONTEXT__.chat.map(function(item) {
                  return JSON.parse(JSON.stringify(item));
                });
              }

              function saveCompatChatContext(context) {
                if (!context || !Array.isArray(context.chat)) return;
                var baseline = Array.isArray(context.__sourceChat) ? context.__sourceChat : [];
                var baselineMap = {};
                baseline.forEach(function(item) {
                  if (item && item.__nodeId) {
                    baselineMap[item.__nodeId] = item;
                  }
                });

                var nextNodeIds = {};
                context.chat.forEach(function(item) {
                  if (!item || !item.__nodeId) return;
                  nextNodeIds[item.__nodeId] = true;
                  var previous = baselineMap[item.__nodeId];
                  if (!previous) return;
                  if (Number(item.swipe_id) !== Number(previous.swipe_id)) {
                    selectHistoryMessageNode(item.__nodeId, Number(item.swipe_id));
                  }
                  if (String(item.mes || '') !== String(previous.mes || '')) {
                    editHistoryMessage(item.__nodeId, String(item.mes || ''));
                  }
                });

                Object.keys(baselineMap).forEach(function(nodeId) {
                  if (!nextNodeIds[nodeId]) {
                    deleteHistoryMessage(nodeId);
                  }
                });

                context.__sourceChat = context.chat.map(function(item) {
                  return JSON.parse(JSON.stringify(item));
                });
                syncCompatContextFromHistory();
              }

              function getSillyTavernContext(forceRefresh) {
                var currentSnapshot = loadHistorySnapshot(forceRefresh === true);
                if (
                  !window.__RH_ST_CONTEXT__ ||
                  forceRefresh === true ||
                  window.__RH_ST_CONTEXT__.__conversationId !== currentSnapshot.conversationId
                ) {
                  var snapshot = currentSnapshot;
                  var chatEntries = snapshot.messages.map(createCompatChatEntry);
                  window.__RH_ST_CONTEXT__ = {
                    __conversationId: snapshot.conversationId,
                    name1: snapshot.userName,
                    name2: snapshot.assistantName,
                    userName: snapshot.userName,
                    charName: snapshot.assistantName,
                    chat: chatEntries,
                    chatMetadata: {},
                    saveChat: async function() {
                      saveCompatChatContext(window.__RH_ST_CONTEXT__);
                    },
                    reloadCurrentChat: function() {
                      var nextSnapshot = loadHistorySnapshot(true);
                      window.__RH_ST_CONTEXT__.chat = nextSnapshot.messages.map(createCompatChatEntry);
                      window.__RH_ST_CONTEXT__.__sourceChat = window.__RH_ST_CONTEXT__.chat.map(function(item) {
                        return JSON.parse(JSON.stringify(item));
                      });
                    },
                  };
                  window.__RH_ST_CONTEXT__.__sourceChat = window.__RH_ST_CONTEXT__.chat.map(function(item) {
                    return JSON.parse(JSON.stringify(item));
                  });
                }
                return window.__RH_ST_CONTEXT__;
              }

              function stripWrappingQuotes(value) {
                if (value.length < 2) return value;
                var first = value.charAt(0);
                var last = value.charAt(value.length - 1);
                if ((first === '"' && last === '"') || (first === "'" && last === "'")) {
                  return decodeSlashEscapes(value.substring(1, value.length - 1));
                }
                return value;
              }

              function decodeSlashEscapes(value) {
                return String(value || '')
                  .replace(/\\\\/g, '\\')
                  .replace(/\\n/g, '\n')
                  .replace(/\\r/g, '\r')
                  .replace(/\\t/g, '\t')
                  .replace(/\\"/g, '"')
                  .replace(/\\'/g, "'");
              }

              function splitCommandChain(raw) {
                var segments = [];
                var current = '';
                var quote = null;
                var escaped = false;

                for (var i = 0; i < raw.length; i++) {
                  var ch = raw.charAt(i);
                  if (escaped) {
                    current += ch;
                    escaped = false;
                    continue;
                  }
                  if (ch === '\\') {
                    current += ch;
                    escaped = true;
                    continue;
                  }
                  if (quote) {
                    if (ch === quote) {
                      quote = null;
                    }
                    current += ch;
                    continue;
                  }
                  if (ch === '"' || ch === "'") {
                    quote = ch;
                    current += ch;
                    continue;
                  }
                  if (ch === '|') {
                    var segment = current.trim();
                    if (segment) segments.push(segment);
                    current = '';
                    continue;
                  }
                  current += ch;
                }

                var lastSegment = current.trim();
                if (lastSegment) segments.push(lastSegment);
                return segments;
              }

              function readCommandPayload(segment, commandName) {
                return stripWrappingQuotes(segment.substring(commandName.length + 1).trim());
              }

              function readSendAsPayload(segment) {
                var rest = segment.substring('/sendas'.length).trim();
                if (!rest) return '';
                var match = rest.match(/^(?:"(?:\\.|[^"])*"|'(?:\\.|[^'])*'|\S+)(?:\s+([\s\S]*))?$/);
                if (!match) return '';
                return stripWrappingQuotes((match[1] || '').trim());
              }

              function handleSlashCommand(command) {
                var raw = String(command || '').trim();
                if (!raw) return;
                var segments = splitCommandChain(raw);
                if (!segments.length) return;

                var pendingSend = null;
                var shouldAnswer = true;
                var triggerSeen = false;

                segments.forEach(function(segment) {
                  var normalized = segment.toLowerCase();

                  if (normalized.indexOf('/setinput ') === 0) {
                    setDraftText(readCommandPayload(segment, 'setinput'));
                    return;
                  }
                  if (normalized === '/setinput') {
                    setDraftText('');
                    return;
                  }
                  if (normalized.indexOf('/input ') === 0) {
                    setDraftText(readCommandPayload(segment, 'input'));
                    return;
                  }
                  if (normalized === '/input') {
                    setDraftText('');
                    return;
                  }
                  if (normalized.indexOf('/addinput ') === 0) {
                    appendDraftText(readCommandPayload(segment, 'addinput'));
                    return;
                  }
                  if (normalized.indexOf('/append ') === 0) {
                    appendDraftText(readCommandPayload(segment, 'append'));
                    return;
                  }
                  if (normalized.indexOf('/comment ') === 0) {
                    appendDraftText(readCommandPayload(segment, 'comment'));
                    return;
                  }
                  if (normalized === '/clearinput' || normalized === '/delinput') {
                    setDraftText('');
                    return;
                  }
                  if (normalized.indexOf('/sendas ') === 0) {
                    pendingSend = readSendAsPayload(segment);
                    return;
                  }
                  if (normalized.indexOf('/send ') === 0) {
                    pendingSend = readCommandPayload(segment, 'send');
                    return;
                  }
                  if (normalized === '/send') {
                    pendingSend = '';
                    return;
                  }
                  if (normalized.indexOf('/trigger') === 0) {
                    triggerSeen = true;
                    shouldAnswer = !/await\s*=\s*false/i.test(segment);
                  }
                });

                if (pendingSend !== null) {
                  sendTextNow(pendingSend, triggerSeen ? shouldAnswer : true);
                  return;
                }
                if (triggerSeen) {
                  sendCurrentDraft(shouldAnswer);
                }
              }

              function installChatComposerShim() {
                if (window.__RH_CHAT_COMPOSER_SHIM__) return;
                window.__RH_CHAT_COMPOSER_SHIM__ = true;

                var composerTextarea = {
                  focus: function() {},
                  blur: function() {},
                  dispatchEvent: function(event) {
                    if (event && event.type === 'input') {
                      setDraftText(this.value);
                    }
                    return true;
                  }
                };
                Object.defineProperty(composerTextarea, 'value', {
                  get: function() { return getDraftText(); },
                  set: function(nextValue) { setDraftText(nextValue); },
                  configurable: true,
                });

                var composerSendButton = {
                  click: function() {
                    sendCurrentDraft(true);
                    return true;
                  }
                };

                var originalQuerySelector = document.querySelector ? document.querySelector.bind(document) : null;
                var originalQuerySelectorAll = document.querySelectorAll ? document.querySelectorAll.bind(document) : null;
                var originalGetElementById = document.getElementById ? document.getElementById.bind(document) : null;
                var originalGetElementsByClassName = document.getElementsByClassName
                  ? document.getElementsByClassName.bind(document)
                  : null;
                var originalGetElementsByTagName = document.getElementsByTagName
                  ? document.getElementsByTagName.bind(document)
                  : null;
                if (originalQuerySelector) {
                  document.querySelector = function(selector) {
                    if (selector === '#send_textarea') return composerTextarea;
                    if (selector === '#send_butt') return composerSendButton;
                    if (isHistorySelector(selector)) {
                      return queryHistorySelectorAll(selector).item(0);
                    }
                    return originalQuerySelector(selector);
                  };
                }
                if (originalQuerySelectorAll) {
                  document.querySelectorAll = function(selector) {
                    if (isHistorySelector(selector)) {
                      return queryHistorySelectorAll(selector);
                    }
                    return originalQuerySelectorAll(selector);
                  };
                }
                if (originalGetElementById) {
                  document.getElementById = function(id) {
                    if (id === 'send_textarea') return composerTextarea;
                    if (id === 'send_butt') return composerSendButton;
                    if (id === 'chat') return createHistoryChatNode();
                    return originalGetElementById(id);
                  };
                }
                if (originalGetElementsByClassName) {
                  document.getElementsByClassName = function(className) {
                    var key = String(className || '').trim();
                    if (key === 'mes' || key === 'mes_text' || key === 'mes_edit' || key === 'mes_edit_done' || key === 'last_mes') {
                      return queryHistorySelectorAll('.' + key);
                    }
                    return originalGetElementsByClassName(className);
                  };
                }
                if (originalGetElementsByTagName) {
                  document.getElementsByTagName = function(tagName) {
                    var key = String(tagName || '').toLowerCase();
                    if (key === 'textarea') {
                      var textareas = queryHistorySelectorAll('textarea');
                      if (textareas.length) {
                        return textareas;
                      }
                    }
                    return originalGetElementsByTagName(tagName);
                  };
                }

                function makeCollection(items) {
                  var api = Array.isArray(items) ? items.slice() : [];
                  api.get = function(index) {
                    return api[index];
                  };
                  api.eq = function(index) {
                    var target = index < 0 ? api[api.length + index] : api[index];
                    return makeCollection(target ? [target] : []);
                  };
                  api.first = function() {
                    return api.eq(0);
                  };
                  api.last = function() {
                    return api.eq(-1);
                  };
                  api.find = function(selector) {
                    var found = [];
                    api.forEach(function(item) {
                      if (item && typeof item.querySelectorAll === 'function') {
                        found = found.concat(Array.prototype.slice.call(item.querySelectorAll(selector)));
                      }
                    });
                    return makeCollection(found);
                  };
                  api.click = function() {
                    api.forEach(function(item) {
                      if (item && typeof item.click === 'function') {
                        item.click();
                      }
                    });
                    return api;
                  };
                  api.trigger = function(eventName) {
                    api.forEach(function(item) {
                      if (!item) return;
                      if (eventName === 'click' && typeof item.click === 'function') {
                        item.click();
                        return;
                      }
                      if (typeof item.dispatchEvent === 'function') {
                        item.dispatchEvent({ type: eventName });
                      }
                    });
                    return api;
                  };
                  api.attr = function(name, value) {
                    if (typeof value === 'undefined') {
                      return api[0] && typeof api[0].getAttribute === 'function'
                        ? api[0].getAttribute(name)
                        : undefined;
                    }
                    api.forEach(function(item) {
                      if (item && typeof item.setAttribute === 'function') {
                        item.setAttribute(name, value);
                      }
                    });
                    return api;
                  };
                  api.text = function(value) {
                    if (typeof value === 'undefined') {
                      return api.map(function(item) {
                        return item && item.textContent != null ? item.textContent : '';
                      }).join('');
                    }
                    api.forEach(function(item) {
                      if (item) {
                        item.textContent = String(value);
                      }
                    });
                    return api;
                  };
                  api.html = function(value) {
                    if (typeof value === 'undefined') {
                      return api[0] && api[0].innerHTML != null ? api[0].innerHTML : '';
                    }
                    api.forEach(function(item) {
                      if (item) {
                        item.innerHTML = String(value);
                      }
                    });
                    return api;
                  };
                  api.val = function(value) {
                    if (typeof value === 'undefined') {
                      return api[0] && api[0].value != null ? api[0].value : '';
                    }
                    api.forEach(function(item) {
                      if (item) {
                        item.value = String(value);
                      }
                    });
                    return api;
                  };
                  api.each = function(callback) {
                    api.forEach(function(item, index) {
                      callback.call(item, index, item);
                    });
                    return api;
                  };
                  api.toArray = function() {
                    return api.slice();
                  };
                  return api;
                }

                function compatDollar(value) {
                  if (typeof value === 'function') {
                    value();
                    return makeCollection([]);
                  }
                  if (typeof value === 'string') {
                    if (isHistorySelector(value)) {
                      return makeCollection(Array.prototype.slice.call(queryHistorySelectorAll(value)));
                    }
                    if (originalQuerySelectorAll) {
                      return makeCollection(Array.prototype.slice.call(originalQuerySelectorAll(value)));
                    }
                    return makeCollection([]);
                  }
                  if (Array.isArray(value)) {
                    return makeCollection(value);
                  }
                  if (value == null) {
                    return makeCollection([]);
                  }
                  return makeCollection([value]);
                }

                var jqueryKey = String.fromCharCode(36);
                if (typeof window[jqueryKey] !== 'function') {
                  window[jqueryKey] = compatDollar;
                }
                if (window.parent && typeof window.parent[jqueryKey] !== 'function') {
                  window.parent[jqueryKey] = compatDollar;
                }

                var sillyTavernCompat = {
                  getContext: function() {
                    return getSillyTavernContext(false);
                  },
                };
                if (!window.SillyTavern) {
                  window.SillyTavern = sillyTavernCompat;
                } else if (typeof window.SillyTavern.getContext !== 'function') {
                  window.SillyTavern.getContext = sillyTavernCompat.getContext;
                }
                if (typeof window.getContext !== 'function') {
                  window.getContext = function() {
                    return window.SillyTavern.getContext();
                  };
                }
                try {
                  if (window.top && window.top.window && !window.top.window.SillyTavern) {
                    window.top.window.SillyTavern = window.SillyTavern;
                  }
                } catch (_err) {}
                if (window.parent && window.parent !== window) {
                  if (!window.parent.SillyTavern) {
                    window.parent.SillyTavern = window.SillyTavern;
                  } else if (typeof window.parent.SillyTavern.getContext !== 'function') {
                    window.parent.SillyTavern.getContext = window.SillyTavern.getContext;
                  }
                }

                if (typeof window.triggerSlash !== 'function') {
                  window.triggerSlash = handleSlashCommand;
                }
                if (window.parent && typeof window.parent.triggerSlash !== 'function') {
                  window.parent.triggerSlash = handleSlashCommand;
                }
                if (typeof window.postMessage !== 'function') {
                  window.postMessage = function() {};
                }
              }

              function updateViewportHeight() {
                document.documentElement.style.setProperty('--TH-viewport-height', window.innerHeight + 'px');
              }

              function reportHeight() {
                var body = document.body;
                var doc = document.documentElement;
                var height = body ? body.scrollHeight : 0;

                // Avoid using offsetHeight/doc offsets as primary signal, because they can
                // reflect current viewport height and keep short content artificially tall.
                if (!Number.isFinite(height) || height <= 0) {
                  height = body ? body.getBoundingClientRect().height : 0;
                }
                if (!Number.isFinite(height) || height <= 0) {
                  height = doc ? doc.scrollHeight : 0;
                }
                if (!Number.isFinite(height) || height <= 0) {
                  height = doc ? doc.getBoundingClientRect().height : 0;
                }
                if (!Number.isFinite(height) || height <= 0) return;
                var nextHeight = Math.ceil(height);
                if (window.__RH_LAST_REPORTED_HEIGHT__ === nextHeight) return;
                window.__RH_LAST_REPORTED_HEIGHT__ = nextHeight;
                try {
                  var bridge = window.$CODE_BLOCK_HEIGHT_BRIDGE_NAME;
                  if (bridge && typeof bridge.onContentHeight === 'function') {
                    bridge.onContentHeight(String(nextHeight));
                  }
                } catch (_err) {}
              }

              function scheduleReportHeight() {
                if (typeof window.requestAnimationFrame === 'function') {
                  window.requestAnimationFrame(reportHeight);
                } else {
                  setTimeout(reportHeight, 0);
                }
              }

              if (window.__RH_CODE_BLOCK_OBSERVER_ATTACHED__) {
                installChatComposerShim();
                updateViewportHeight();
                scheduleReportHeight();
                return;
              }
              window.__RH_CODE_BLOCK_OBSERVER_ATTACHED__ = true;
              installChatComposerShim();

              function observeHeightChanges() {
                if (typeof ResizeObserver === 'function') {
                  var resizeObserver = new ResizeObserver(function() {
                    scheduleReportHeight();
                  });
                  if (document.documentElement) resizeObserver.observe(document.documentElement);
                  if (document.body) resizeObserver.observe(document.body);
                } else if (typeof MutationObserver === 'function' && document.body) {
                  var mutationObserver = new MutationObserver(function() {
                    scheduleReportHeight();
                  });
                  mutationObserver.observe(document.body, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    characterData: true
                  });
                }
              }

              updateViewportHeight();
              window.addEventListener('resize', function() {
                updateViewportHeight();
                scheduleReportHeight();
              });

              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', function() {
                  observeHeightChanges();
                  scheduleReportHeight();
                });
              } else {
                observeHeightChanges();
                scheduleReportHeight();
              }

              window.addEventListener('load', function() {
                scheduleReportHeight();
                setTimeout(scheduleReportHeight, 120);
                setTimeout(scheduleReportHeight, 360);
              });
            })();
            </script>
        """.trimIndent()
    }

    private fun extractXmlRootTagName(code: String): String? {
        val leadingMisc = XML_LEADING_MISC_REGEX.find(code)?.value.orEmpty()
        val remaining = code.substring(leadingMisc.length)
        val match = XML_ROOT_TAG_REGEX.find(remaining) ?: return null
        return match.groupValues[1]
            .substringAfterLast(':')
            .lowercase()
    }

    private fun insertBefore(
        content: String,
        index: Int,
        insertion: String,
    ): String {
        return buildString(content.length + insertion.length) {
            append(content, 0, index)
            append(insertion)
            append(content, index, content.length)
        }
    }

    private fun insertAfter(
        content: String,
        index: Int,
        insertion: String,
    ): String {
        return buildString(content.length + insertion.length) {
            append(content, 0, index)
            append(insertion)
            append(content, index, content.length)
        }
    }
}
