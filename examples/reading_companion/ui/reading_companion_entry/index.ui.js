const TOOL_PACKAGE = "reading_companion";
const AUTO_COMMENTARY_PACKAGE = "reading_companion_auto_commentary";
const COMPANION_CHAT_MAP_ENV_KEY =
  "OPERIT_READING_COMPANION_CHAT_MAP_V1";

function useStateValue(ctx, key, initialValue) {
  const pair = ctx.useState(key, initialValue);
  return { value: pair[0], set: pair[1] };
}

function useEnglishLocale() {
  return String(getLang() || "")
    .trim()
    .toLowerCase()
    .startsWith("en");
}

function toErrorText(error) {
  if (error && typeof error === "object" && error.message) {
    return String(error.message);
  }
  return String(error || "");
}

function parseJson(value) {
  if (typeof value !== "string") {
    return value;
  }
  const raw = value.trim();
  if (!raw) {
    return "";
  }
  try {
    return JSON.parse(raw);
  } catch (_error) {
    return value;
  }
}

function unwrapToolResult(value) {
  let current = parseJson(value);
  for (let depth = 0; depth < 6; depth += 1) {
    if (!current || typeof current !== "object" || Array.isArray(current)) {
      return current;
    }
    if (current.success === false) {
      throw new Error(
        String(current.message || current.error || "Operation failed"),
      );
    }
    if (
      Object.prototype.hasOwnProperty.call(current, "data") &&
      current.data !== current
    ) {
      current = parseJson(current.data);
      continue;
    }
    if (
      Object.prototype.hasOwnProperty.call(current, "result") &&
      current.result !== current
    ) {
      current = parseJson(current.result);
      continue;
    }
    return current;
  }
  return current;
}

function readChatMap(ctx) {
  const raw = String(ctx.getEnv(COMPANION_CHAT_MAP_ENV_KEY) || "").trim();
  if (!raw) {
    return {};
  }
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return {};
    }
    const clean = {};
    Object.keys(parsed).forEach((bookId) => {
      const normalizedBookId = String(bookId || "").trim();
      const normalizedChatId = String(parsed[bookId] || "").trim();
      if (normalizedBookId && normalizedChatId) {
        clean[normalizedBookId] = normalizedChatId;
      }
    });
    return clean;
  } catch (_error) {
    return {};
  }
}

async function resolveToolName(ctx, packageName, toolName) {
  if (ctx.resolveToolName) {
    const resolved = await ctx.resolveToolName({
      packageName,
      toolName,
      preferImported: true,
    });
    const value = String(resolved || "").trim();
    if (value) {
      return value;
    }
  }
  return `${packageName}:${toolName}`;
}

async function callPackageTool(ctx, packageName, toolName, parameters) {
  if (ctx.usePackage) {
    await ctx.usePackage(packageName);
  }
  const resolved = await resolveToolName(ctx, packageName, toolName);
  const candidates = [
    resolved,
    `${packageName}:${toolName}`,
  ].filter((item, index, values) => item && values.indexOf(item) === index);
  let lastError = "";
  for (let index = 0; index < candidates.length; index += 1) {
    try {
      return unwrapToolResult(
        await ctx.callTool(candidates[index], parameters || {}),
      );
    } catch (error) {
      lastError = toErrorText(error);
    }
  }
  throw new Error(lastError || `${toolName} failed`);
}

function getText(useEnglish) {
  if (useEnglish) {
    return {
      title: "AI Reading Companion",
      description:
        "A dedicated chat for each Legado book, with plot reactions, recall, and optional AI paragraph commentary.",
      basicTitle: "Reading companion",
      basicOn: "Connected to Legado and available in companion chats.",
      basicOff: "Turn this on before starting a companion chat.",
      autoTitle: "AI auto commentary",
      autoOn:
        "On. After progress changes, next-chapter commentary is queued in about 20 seconds. Private context is capped at 8 chapters / 48,000 characters and trimmed to the selected model window.",
      autoOff:
        "Off. Enabling permits an isolated generator to read the next chapter and bounded prior context, dynamically trimmed to the selected model window, and spend model tokens.",
      personaTitle: "Commentary character",
      personaHint:
        "This character writes the comments, appears as their author in Legado, and uses the same dedicated companion chat.",
      personaSelect: "Select a character card",
      personaLoading: "Loading character cards…",
      personaNoCards: "No character cards are available.",
      personaSelected: "Commentary character updated.",
      personaRequired: "Select a commentary character first.",
      personaLoadFailed: "Could not load character cards: ",
      personaSaveFailed: "Could not select the commentary character: ",
      loading: "Checking Legado and companion status…",
      disconnectedTitle: "Legado is not ready",
      disconnectedBody:
        "Install or open Legado, open a book, then return here and refresh.",
      connectedTitle: "Current book",
      chapterPrefix: "Chapter",
      preciseProgress: "Precise reading position available",
      chapterProgress: "Only chapter-level progress is available",
      start: "Continue companion chat",
      create: "Start companion chat",
      preparing: "Preparing your dedicated chat…",
      refresh: "Refresh status",
      manage: "Open package management",
      regenerate: "Generate next-chapter comments now",
      regenerating: "Generating comments with the selected model…",
      latestRun: "Latest commentary task",
      noRun: "No commentary task has run yet.",
      comments: "comments",
      noNext: "The current book has no next chapter.",
      generated: "Generated",
      cached: "Ready from cache",
      generating: "Generating",
      interrupted: "Interrupted",
      cancelled: "Cancelled",
      failed: "Failed",
      superseded: "Skipped after reading state changed",
      runErrors: {
        model_timeout: "The model request timed out.",
        model_context_too_small:
          "The selected model cannot fit the character card and chapter text. Select a long-context model and try again.",
        legado_not_installed: "Legado is not installed.",
        legado_connection_failed: "Could not connect to Legado.",
        legado_empty_bookshelf: "The Legado bookshelf is empty.",
        legado_no_recent_book: "No recently read Legado book was found.",
        legado_chapter_read_failed: "Could not read the chapter from Legado.",
        legado_unsafe_position: "The reading position could not be read safely.",
        legado_invalid_response: "Legado returned invalid data.",
        invalid_model_response: "The model returned invalid commentary data.",
        role_not_selected: "Select a commentary character for this book first.",
        role_unavailable:
          "The selected commentary character no longer exists. Select another card.",
        cancelled: "The task was cancelled by the system or user.",
        interrupted: "The task was interrupted before it finished.",
        unknown_error: "The task failed for an unknown reason.",
      },
      queuedHint:
        "Background history stays on this device in reading_companion.db. The status below never exposes unread text or comments.",
      enableBasicFailed: "Could not change reading companion state: ",
      enableAutoFailed: "Could not change auto commentary state: ",
      enableStateMismatch: "The package did not turn on.",
      disableStateMismatch: "The package did not turn off.",
      regenerateFailed: "Could not generate next-chapter comments: ",
      loadFailed: "Status check failed: ",
      prepareFailed: "Could not open the companion chat: ",
      readyNotice: "Status refreshed.",
      generatedNotice: "The next chapter commentary task finished.",
      chatTitlePrefix: "Reading · ",
      existingChat: "A separate chat is reused for this book.",
      newChat: "A separate chat will be created for this book.",
      hint:
        "Disabling the ToolPkg removes this sidebar entry. Ordinary chats do not receive the reading-companion prompt.",
    };
  }
  return {
    title: "AI 阅读伴侣",
    description:
      "每本 Legado 书籍都有独立会话，可交流吐槽、回顾前文，并按需开启 AI 自动段评。",
    basicTitle: "阅读伴侣",
    basicOn: "已连接工具能力，仅在专属伴读会话中生效。",
    basicOff: "开始伴读前需要先开启。",
    autoTitle: "AI 自动段评",
    autoOn:
      "已开启。阅读进度变化后约 20 秒排队生成下一章段评；隔离前情上限为最近 8 章、4.8 万字，实际按所选模型窗口裁剪。",
    autoOff:
      "已关闭。开启即表示允许隔离生成器读取下一章及有限前情；实际前情按所选模型窗口裁剪，并产生模型 Token 消耗。",
    personaTitle: "段评角色",
    personaHint:
      "由这个角色写段评；Legado 会把角色卡名字显示为段评作者，专属伴读会话也使用同一角色。",
    personaSelect: "选择一个角色卡",
    personaLoading: "正在加载角色卡…",
    personaNoCards: "当前没有可用的角色卡。",
    personaSelected: "段评角色已更新。",
    personaRequired: "请先选择一个段评角色。",
    personaLoadFailed: "加载角色卡失败：",
    personaSaveFailed: "选择段评角色失败：",
    loading: "正在检查 Legado 与伴读状态…",
    disconnectedTitle: "Legado 尚未就绪",
    disconnectedBody: "请安装或打开 Legado，进入一本书阅读后，回来刷新状态。",
    connectedTitle: "当前伴读书籍",
    chapterPrefix: "第",
    preciseProgress: "已获取精确阅读位置",
    chapterProgress: "当前只能获取章节级进度",
    start: "继续伴读",
    create: "开始伴读",
    preparing: "正在准备专属伴读会话…",
    refresh: "刷新状态",
    manage: "打开包管理",
    regenerate: "立即生成下一章段评",
    regenerating: "正在使用所选模型生成段评…",
    latestRun: "最近一次段评任务",
    noRun: "还没有执行过段评任务。",
    comments: "条段评",
    noNext: "当前书籍已经没有下一章。",
    generated: "已生成",
    cached: "已命中缓存",
    generating: "生成中",
    interrupted: "已中断",
    cancelled: "已取消",
    failed: "失败",
    superseded: "阅读状态已变化，已跳过",
    runErrors: {
      model_timeout: "模型调用超时。",
      model_context_too_small:
        "所选模型上下文不足以容纳角色卡和本章正文，请换用长上下文模型后重试。",
      legado_not_installed: "Legado 未安装。",
      legado_connection_failed: "无法连接 Legado。",
      legado_empty_bookshelf: "Legado 书架为空。",
      legado_no_recent_book: "未找到最近阅读书籍。",
      legado_chapter_read_failed: "读取章节失败。",
      legado_unsafe_position: "阅读位置不可安全读取。",
      legado_invalid_response: "Legado 返回数据无效。",
      invalid_model_response: "模型返回的段评数据无效。",
      role_not_selected: "请先为当前书籍选择段评角色。",
      role_unavailable: "所选段评角色已不存在，请重新选择。",
      cancelled: "任务被系统或用户取消。",
      interrupted: "任务进程中断。",
      unknown_error: "任务因未知原因失败。",
    },
    queuedHint:
      "后台历史保存在本机 reading_companion.db；这里不会显示未读正文或尚未解锁的段评内容。",
    enableBasicFailed: "切换阅读伴侣失败：",
    enableAutoFailed: "切换自动段评失败：",
    enableStateMismatch: "工具包未能开启。",
    disableStateMismatch: "工具包未能关闭。",
    regenerateFailed: "生成下一章段评失败：",
    loadFailed: "状态检查失败：",
    prepareFailed: "打开伴读会话失败：",
    readyNotice: "状态已刷新。",
    generatedNotice: "下一章段评任务已完成。",
    chatTitlePrefix: "伴读 · ",
    existingChat: "这本书会复用已有的独立伴读会话。",
    newChat: "这本书会创建一个独立伴读会话。",
    hint:
      "关闭整个工具包后，侧栏入口会消失；普通聊天不会注入伴读提示词。",
  };
}

function statusLabel(text, status) {
  const normalized = String(status || "").trim().toLowerCase();
  const labels = {
    generated: text.generated,
    cached: text.cached,
    generating: text.generating,
    running: text.generating,
    interrupted: text.interrupted,
    cancelled: text.cancelled,
    failed: text.failed,
    superseded: text.superseded,
    no_next_chapter: text.noNext,
    already_generating: text.generating,
  };
  return labels[normalized] || normalized || text.noRun;
}

function statusColor(status) {
  const normalized = String(status || "").trim().toLowerCase();
  if (
    normalized === "generated" ||
    normalized === "cached" ||
    normalized === "no_next_chapter"
  ) {
    return "primary";
  }
  if (normalized === "generating" || normalized === "running") {
    return "tertiary";
  }
  if (
    normalized === "failed" ||
    normalized === "interrupted" ||
    normalized === "cancelled" ||
    normalized === "superseded"
  ) {
    return "error";
  }
  return "onSurfaceVariant";
}

function autoCommentErrorLabel(text, error) {
  const raw = String(error || "").trim();
  const legacyCodes = {
    "模型调用超时": "model_timeout",
    "Legado 未安装": "legado_not_installed",
    "无法连接 Legado": "legado_connection_failed",
    "Legado 书架为空": "legado_empty_bookshelf",
    "未找到最近阅读书籍": "legado_no_recent_book",
    "读取章节失败": "legado_chapter_read_failed",
    "阅读位置不可安全读取": "legado_unsafe_position",
    "Legado 返回数据无效": "legado_invalid_response",
    "模型返回格式无效": "invalid_model_response",
    "任务被系统或用户取消": "cancelled",
    "任务进程中断": "interrupted",
    "未知错误": "unknown_error",
  };
  const code = legacyCodes[raw] || raw;
  return text.runErrors[code] || text.runErrors.unknown_error;
}

function settingRow(ctx, title, subtitle, checked, enabled, onCheckedChange) {
  return ctx.UI.Row(
    {
      fillMaxWidth: true,
      verticalAlignment: "center",
      horizontalArrangement: "spaceBetween",
    },
    [
      ctx.UI.Column({ weight: 1, spacing: 4 }, [
        ctx.UI.Text({
          text: title,
          style: "titleSmall",
          color: "onSurface",
        }),
        ctx.UI.Text({
          text: subtitle,
          style: "bodySmall",
          color: "onSurfaceVariant",
        }),
      ]),
      ctx.UI.Spacer({ width: 12 }),
      ctx.UI.Switch({
        checked,
        enabled,
        onCheckedChange,
      }),
    ],
  );
}

function readingCompanionEntryScreen(ctx) {
  const useEnglish = useEnglishLocale();
  const text = getText(useEnglish);
  const colors = ctx.MaterialTheme.colorScheme;
  const initializedState = useStateValue(ctx, "initialized", false);
  const loadingState = useStateValue(ctx, "loading", true);
  const busyState = useStateValue(ctx, "busy", false);
  const busyLabelState = useStateValue(ctx, "busyLabel", "");
  const basicEnabledState = useStateValue(ctx, "basicEnabled", false);
  const autoEnabledState = useStateValue(ctx, "autoEnabled", false);
  const readingState = useStateValue(ctx, "readingState", null);
  const autoStatusState = useStateValue(ctx, "autoStatus", null);
  const selectedPersonaState = useStateValue(ctx, "selectedPersona", null);
  const availableCardsState = useStateValue(ctx, "availableCards", []);
  const showCardPickerState = useStateValue(ctx, "showCardPicker", false);
  const loadingCardsState = useStateValue(ctx, "loadingCards", false);
  const cardsLoadedState = useStateValue(ctx, "cardsLoaded", false);
  const errorState = useStateValue(ctx, "error", "");
  const noticeState = useStateValue(ctx, "notice", "");

  const loadDashboard = async (showNotice) => {
    loadingState.set(true);
    errorState.set("");
    try {
      const basicEnabled = ctx.isPackageImported
        ? !!(await ctx.isPackageImported(TOOL_PACKAGE))
        : false;
      const autoEnabled = ctx.isPackageImported
        ? !!(await ctx.isPackageImported(AUTO_COMMENTARY_PACKAGE))
        : false;
      basicEnabledState.set(basicEnabled);
      autoEnabledState.set(autoEnabled);
      readingState.set(null);
      autoStatusState.set(null);
      selectedPersonaState.set(null);

      const errors = [];
      let currentBook = null;
      if (basicEnabled) {
        try {
          currentBook = await callPackageTool(
            ctx,
            TOOL_PACKAGE,
            "get_current_book",
            {},
          );
          readingState.set(currentBook);
          const bookId = String(currentBook && currentBook.bookId || "").trim();
          if (bookId) {
            if (!ctx.getReadingCompanionCommentaryCharacter) {
              throw new Error("Commentary character selection is unavailable");
            }
            selectedPersonaState.set(
              await ctx.getReadingCompanionCommentaryCharacter(bookId),
            );
          }
        } catch (error) {
          errors.push(toErrorText(error));
        }
      }
      if (autoEnabled) {
        try {
          autoStatusState.set(
            await callPackageTool(
              ctx,
              AUTO_COMMENTARY_PACKAGE,
              "auto_commentary_status",
              {},
            ),
          );
        } catch (error) {
          errors.push(toErrorText(error));
        }
      }
      if (errors.length > 0) {
        errorState.set(`${text.loadFailed}${errors.join(" · ")}`);
      } else if (showNotice) {
        noticeState.set(text.readyNotice);
      }
    } catch (error) {
      errorState.set(`${text.loadFailed}${toErrorText(error)}`);
    } finally {
      loadingState.set(false);
    }
  };

  const setPackageEnabled = async (packageName, enabled) => {
    if (enabled) {
      if (ctx.importPackage) {
        await ctx.importPackage(packageName);
      }
      if (ctx.usePackage) {
        await ctx.usePackage(packageName);
      }
    } else if (ctx.removePackage) {
      await ctx.removePackage(packageName);
    }
    if (!ctx.isPackageImported) {
      throw new Error(
        enabled ? text.enableStateMismatch : text.disableStateMismatch,
      );
    }
    const actualEnabled = !!(await ctx.isPackageImported(packageName));
    if (actualEnabled !== enabled) {
      throw new Error(
        enabled ? text.enableStateMismatch : text.disableStateMismatch,
      );
    }
  };

  const queryCharacterCards = async () => {
    loadingCardsState.set(true);
    try {
      if (
        typeof Tools === "undefined" ||
        !Tools ||
        !Tools.Chat ||
        !Tools.Chat.listCharacterCards
      ) {
        throw new Error("Character card API is unavailable");
      }
      const result = await Tools.Chat.listCharacterCards();
      const cards = (Array.isArray(result && result.cards) ? result.cards : [])
        .map((card) => ({
          id: String(card && card.id || "").trim(),
          name: String(card && card.name || "").trim(),
          description: String(card && card.description || "").trim(),
        }))
        .filter((card) => card.id && card.name);
      availableCardsState.set(cards);
      cardsLoadedState.set(true);
      return cards;
    } finally {
      loadingCardsState.set(false);
    }
  };

  const loadCardPicker = async () => {
    if (showCardPickerState.value) {
      showCardPickerState.set(false);
      return;
    }
    showCardPickerState.set(true);
    if (cardsLoadedState.value) {
      return;
    }
    try {
      await queryCharacterCards();
    } catch (error) {
      showCardPickerState.set(false);
      errorState.set(`${text.personaLoadFailed}${toErrorText(error)}`);
    }
  };

  const selectCommentaryCharacter = async (card) => {
    const book = readingState.value;
    if (
      busyState.value ||
      !book ||
      !card ||
      !String(card.id || "").trim()
    ) {
      return;
    }
    busyState.set(true);
    noticeState.set("");
    errorState.set("");
    try {
      if (!ctx.setReadingCompanionCommentaryCharacter) {
        throw new Error("Commentary character selection is unavailable");
      }
      const bookId = String(book.bookId || "").trim();
      const chatMap = readChatMap(ctx);
      const chatId = String(chatMap[bookId] || "").trim();
      const selected = await ctx.setReadingCompanionCommentaryCharacter({
        bookId,
        roleCardId: String(card.id).trim(),
        chatId,
      });
      selectedPersonaState.set(selected);
      showCardPickerState.set(false);
      noticeState.set(text.personaSelected);
    } catch (error) {
      errorState.set(`${text.personaSaveFailed}${toErrorText(error)}`);
    } finally {
      busyState.set(false);
    }
  };

  const toggleBasic = async (checked) => {
    if (busyState.value) {
      return;
    }
    busyState.set(true);
    noticeState.set("");
    errorState.set("");
    try {
      if (!checked && autoEnabledState.value) {
        await setPackageEnabled(AUTO_COMMENTARY_PACKAGE, false);
      }
      await setPackageEnabled(TOOL_PACKAGE, checked);
      await loadDashboard(false);
    } catch (error) {
      const message = `${text.enableBasicFailed}${toErrorText(error)}`;
      await loadDashboard(false);
      errorState.set(message);
    } finally {
      busyState.set(false);
    }
  };

  const toggleAuto = async (checked) => {
    if (busyState.value) {
      return;
    }
    if (
      checked &&
      !String(
        selectedPersonaState.value &&
          selectedPersonaState.value.roleCardId ||
          "",
      ).trim()
    ) {
      errorState.set(text.personaRequired);
      return;
    }
    busyState.set(true);
    noticeState.set("");
    errorState.set("");
    try {
      if (checked && !basicEnabledState.value) {
        await setPackageEnabled(TOOL_PACKAGE, true);
      }
      await setPackageEnabled(AUTO_COMMENTARY_PACKAGE, checked);
      await loadDashboard(false);
    } catch (error) {
      const message = `${text.enableAutoFailed}${toErrorText(error)}`;
      await loadDashboard(false);
      errorState.set(message);
    } finally {
      busyState.set(false);
    }
  };

  const prepareCompanionChat = async () => {
    const book = readingState.value;
    const roleCardId = String(
      selectedPersonaState.value &&
        selectedPersonaState.value.roleCardId ||
        "",
    ).trim();
    if (
      busyState.value ||
      !book ||
      !String(book.bookId || "").trim()
    ) {
      return;
    }
    if (!roleCardId) {
      errorState.set(text.personaRequired);
      return;
    }
    busyState.set(true);
    busyLabelState.set(text.preparing);
    noticeState.set("");
    errorState.set("");
    try {
      const bookId = String(book.bookId).trim();
      const chatMap = readChatMap(ctx);
      let chatId = String(chatMap[bookId] || "").trim();
      let chatActivated = false;
      if (chatId) {
        try {
          if (!ctx.setReadingCompanionCommentaryCharacter) {
            throw new Error("Commentary character selection is unavailable");
          }
          await ctx.setReadingCompanionCommentaryCharacter({
            bookId,
            roleCardId,
            chatId,
          });
          if (!ctx.activateReadingCompanionChat) {
            throw new Error("Companion chat activation is unavailable");
          }
          await ctx.activateReadingCompanionChat(chatId);
          chatActivated = true;
        } catch (_error) {
          delete chatMap[bookId];
          chatId = "";
        }
      }
      if (!chatId) {
        if (!ctx.createReadingCompanionChat) {
          throw new Error("Companion chat creation is unavailable");
        }
        const created = await ctx.createReadingCompanionChat({
          title: `${text.chatTitlePrefix}${String(book.book || "").trim()}`,
          characterCardId: roleCardId,
        });
        chatId = String(
          (created && (created.chatId || created.chat_id)) || "",
        ).trim();
        if (!chatId) {
          throw new Error("The new chat did not return a chat ID");
        }
      }

      if (!ctx.activateReadingCompanionChat) {
        throw new Error("Companion chat activation is unavailable");
      }
      if (!ctx.setReadingCompanionCommentaryCharacter) {
        throw new Error("Commentary character selection is unavailable");
      }
      await ctx.setReadingCompanionCommentaryCharacter({
        bookId,
        roleCardId,
        chatId,
      });
      if (!chatActivated) {
        await ctx.activateReadingCompanionChat(chatId);
      }
      chatMap[bookId] = chatId;
      await ctx.setEnv(COMPANION_CHAT_MAP_ENV_KEY, JSON.stringify(chatMap));
      await ctx.navigate("native.ai_chat");
    } catch (error) {
      errorState.set(`${text.prepareFailed}${toErrorText(error)}`);
    } finally {
      busyState.set(false);
      busyLabelState.set("");
    }
  };

  const regenerateComments = async () => {
    if (busyState.value || !autoEnabledState.value) {
      return;
    }
    if (
      !String(
        selectedPersonaState.value &&
          selectedPersonaState.value.roleCardId ||
          "",
      ).trim()
    ) {
      errorState.set(text.personaRequired);
      return;
    }
    busyState.set(true);
    busyLabelState.set(text.regenerating);
    noticeState.set("");
    errorState.set("");
    try {
      await callPackageTool(
        ctx,
        AUTO_COMMENTARY_PACKAGE,
        "regenerate_next_chapter_comments",
        {},
      );
      noticeState.set(text.generatedNotice);
      await loadDashboard(false);
    } catch (error) {
      errorState.set(`${text.regenerateFailed}${toErrorText(error)}`);
    } finally {
      busyState.set(false);
      busyLabelState.set("");
    }
  };

  const book = readingState.value;
  const chatMap = readChatMap(ctx);
  const hasExistingChat =
    !!book &&
    !!String(book.bookId || "").trim() &&
    !!String(chatMap[String(book.bookId).trim()] || "").trim();
  const latestRun =
    autoStatusState.value &&
    autoStatusState.value.latestRun &&
    typeof autoStatusState.value.latestRun === "object"
      ? autoStatusState.value.latestRun
      : null;
  const selectedRoleCardId = String(
    selectedPersonaState.value &&
      selectedPersonaState.value.roleCardId ||
      "",
  ).trim();
  const selectedRoleCard = availableCardsState.value.find(
    (card) => card.id === selectedRoleCardId,
  );
  const selectedRoleCardName = String(
    selectedRoleCard && selectedRoleCard.name ||
      selectedPersonaState.value &&
        selectedPersonaState.value.roleCardName ||
      "",
  ).trim();

  const children = [
    ctx.UI.Card(
      {
        fillMaxWidth: true,
        containerColor: colors.surface,
        elevation: 1,
      },
      ctx.UI.Column(
        {
          fillMaxWidth: true,
          padding: 20,
          spacing: 12,
          horizontalAlignment: "center",
        },
        [
          ctx.UI.Icon({
            name: "Book",
            size: 42,
            tint: colors.primary,
          }),
          ctx.UI.Text({
            text: text.title,
            style: "headlineSmall",
            color: colors.onSurface,
          }),
          ctx.UI.Text({
            text: text.description,
            style: "bodyMedium",
            color: colors.onSurfaceVariant,
          }),
        ],
      ),
    ),
    ctx.UI.Card(
      {
        fillMaxWidth: true,
        containerColor: colors.surfaceVariant,
      },
      ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 16 }, [
        settingRow(
          ctx,
          text.basicTitle,
          basicEnabledState.value ? text.basicOn : text.basicOff,
          basicEnabledState.value,
          !busyState.value,
          toggleBasic,
        ),
        settingRow(
          ctx,
          text.autoTitle,
          autoEnabledState.value ? text.autoOn : text.autoOff,
          autoEnabledState.value,
          !busyState.value,
          toggleAuto,
        ),
      ]),
    ),
  ];

  if (loadingState.value) {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.primaryContainer,
        },
        ctx.UI.Row(
          {
            fillMaxWidth: true,
            padding: 16,
            spacing: 12,
            verticalAlignment: "center",
          },
          [
            ctx.UI.CircularProgressIndicator({
              width: 20,
              height: 20,
              strokeWidth: 2,
            }),
            ctx.UI.Text({
              text: text.loading,
              style: "bodyMedium",
              color: colors.onPrimaryContainer,
            }),
          ],
        ),
      ),
    );
  } else if (book) {
    const chapterText = useEnglish
      ? `${text.chapterPrefix} ${book.currentChapterNumber || "?"}`
      : `${text.chapterPrefix}${book.currentChapterNumber || "?"}章`;
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.primaryContainer,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          ctx.UI.Text({
            text: text.connectedTitle,
            style: "labelLarge",
            color: colors.onPrimaryContainer,
          }),
          ctx.UI.Text({
            text: String(book.book || ""),
            style: "titleLarge",
            color: colors.onPrimaryContainer,
          }),
          ctx.UI.Text({
            text: `${chapterText} · ${String(book.currentChapterTitle || "")}`,
            style: "bodyMedium",
            color: colors.onPrimaryContainer,
          }),
          ctx.UI.Text({
            text: book.preciseCurrentPositionAvailable
              ? text.preciseProgress
              : text.chapterProgress,
            style: "bodySmall",
            color: colors.onPrimaryContainer,
          }),
          ctx.UI.Text({
            text: hasExistingChat ? text.existingChat : text.newChat,
            style: "bodySmall",
            color: colors.onPrimaryContainer,
          }),
        ]),
      ),
    );
  } else if (basicEnabledState.value) {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.errorContainer,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          ctx.UI.Text({
            text: text.disconnectedTitle,
            style: "titleMedium",
            color: colors.onErrorContainer,
          }),
          ctx.UI.Text({
            text: text.disconnectedBody,
            style: "bodyMedium",
            color: colors.onErrorContainer,
          }),
        ]),
      ),
    );
  }

  if (book) {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.surface,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
          ctx.UI.Text({
            text: text.personaTitle,
            style: "titleMedium",
            color: colors.onSurface,
          }),
          ctx.UI.Box({ fillMaxWidth: true }, [
            ctx.UI.OutlinedButton(
              {
                fillMaxWidth: true,
                enabled: !busyState.value,
                onClick: loadCardPicker,
              },
              [
                ctx.UI.Row(
                  {
                    fillMaxWidth: true,
                    horizontalArrangement: "spaceBetween",
                    verticalAlignment: "center",
                  },
                  [
                    ctx.UI.Text({
                      text: selectedRoleCardName || text.personaSelect,
                      color: colors.onSurface,
                      fontWeight: selectedRoleCardName ? "medium" : "normal",
                      maxLines: 1,
                      overflow: "ellipsis",
                    }),
                    ctx.UI.Icon({
                      name: showCardPickerState.value
                        ? "arrowDropUp"
                        : "arrowDropDown",
                      tint: colors.onSurfaceVariant,
                      size: 20,
                    }),
                  ],
                ),
              ],
            ),
            ctx.UI.DropdownMenu(
              {
                expanded: showCardPickerState.value,
                modifier: ctx.Modifier.heightIn({ maxHeight: 520 }),
                properties: {
                  focusable: true,
                  usePlatformDefaultWidth: false,
                },
                onDismissRequest: () => showCardPickerState.set(false),
              },
              loadingCardsState.value
                ? [
                    ctx.UI.Box(
                      {
                        modifier: ctx.Modifier
                          .fillMaxWidth()
                          .padding({ horizontal: 16, vertical: 12 }),
                      },
                      [
                        ctx.UI.Text({
                          text: text.personaLoading,
                          color: colors.onSurfaceVariant,
                        }),
                      ],
                    ),
                  ]
                : availableCardsState.value.length === 0
                  ? [
                      ctx.UI.Box(
                        {
                          modifier: ctx.Modifier
                            .fillMaxWidth()
                            .padding({ horizontal: 16, vertical: 12 }),
                        },
                        [
                          ctx.UI.Text({
                            text: text.personaNoCards,
                            color: colors.onSurfaceVariant,
                          }),
                        ],
                      ),
                    ]
                  : availableCardsState.value.map((card) =>
                      ctx.UI.Box(
                        {
                          modifier: ctx.Modifier
                            .fillMaxWidth()
                            .clickable(() => selectCommentaryCharacter(card))
                            .padding({ horizontal: 16, vertical: 12 }),
                        },
                        [
                          ctx.UI.Row(
                            {
                              fillMaxWidth: true,
                              horizontalArrangement: "spaceBetween",
                              verticalAlignment: "center",
                            },
                            [
                              ctx.UI.Column({ weight: 1, spacing: 2 }, [
                                ctx.UI.Text({
                                  text: card.name,
                                  color: colors.onSurface,
                                  fontWeight:
                                    card.id === selectedRoleCardId
                                      ? "bold"
                                      : "normal",
                                  maxLines: 1,
                                  overflow: "ellipsis",
                                }),
                                card.description
                                  ? ctx.UI.Text({
                                      text: card.description,
                                      style: "bodySmall",
                                      color: colors.onSurfaceVariant,
                                      maxLines: 1,
                                      overflow: "ellipsis",
                                    })
                                  : ctx.UI.Spacer({ height: 0 }),
                              ]),
                              card.id === selectedRoleCardId
                                ? ctx.UI.Icon({
                                    name: "check",
                                    tint: colors.primary,
                                    size: 18,
                                  })
                                : ctx.UI.Spacer({ width: 18 }),
                            ],
                          ),
                        ],
                      ),
                    ),
            ),
          ]),
          ctx.UI.Text({
            text: text.personaHint,
            style: "bodySmall",
            color: colors.onSurfaceVariant,
          }),
        ]),
      ),
    );
  }

  if (autoEnabledState.value) {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.surface,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          ctx.UI.Text({
            text: text.latestRun,
            style: "titleMedium",
            color: colors.onSurface,
          }),
          ctx.UI.Text({
            text: latestRun
              ? `${statusLabel(text, latestRun.status)}${
                  Number(latestRun.commentCount || 0) > 0
                    ? ` · ${latestRun.commentCount} ${text.comments}`
                    : ""
                }`
              : text.noRun,
            style: "bodyMedium",
            color: latestRun
              ? statusColor(latestRun.status)
              : colors.onSurfaceVariant,
          }),
          latestRun && latestRun.roleCardName
            ? ctx.UI.Text({
                text: `${String(latestRun.roleCardName)} · ${String(
                  latestRun.modelConfigName ||
                    latestRun.model ||
                    latestRun.provider ||
                    "",
                )}`,
                style: "bodySmall",
                color: colors.onSurfaceVariant,
              })
            : ctx.UI.Spacer({ height: 0 }),
          latestRun && latestRun.error
            ? ctx.UI.Text({
                text: autoCommentErrorLabel(text, latestRun.error),
                style: "bodySmall",
                color: colors.error,
              })
            : ctx.UI.Spacer({ height: 0 }),
          ctx.UI.Text({
            text: text.queuedHint,
            style: "bodySmall",
            color: colors.onSurfaceVariant,
          }),
        ]),
      ),
    );
  }

  if (noticeState.value) {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.secondaryContainer,
        },
        ctx.UI.Text({
          text: noticeState.value,
          style: "bodyMedium",
          color: colors.onSecondaryContainer,
          padding: 14,
        }),
      ),
    );
  }

  if (errorState.value) {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.errorContainer,
        },
        ctx.UI.Text({
          text: errorState.value,
          style: "bodyMedium",
          color: colors.onErrorContainer,
          padding: 14,
        }),
      ),
    );
  }

  if (busyState.value && busyLabelState.value) {
    children.push(
      ctx.UI.Row(
        {
          fillMaxWidth: true,
          spacing: 10,
          verticalAlignment: "center",
        },
        [
          ctx.UI.CircularProgressIndicator({
            width: 18,
            height: 18,
            strokeWidth: 2,
          }),
          ctx.UI.Text({
            text: busyLabelState.value,
            style: "bodyMedium",
            color: colors.onSurfaceVariant,
          }),
        ],
      ),
    );
  }

  children.push(
    ctx.UI.Button(
      {
        fillMaxWidth: true,
        enabled:
          !!book &&
          basicEnabledState.value &&
          !loadingState.value &&
          !busyState.value,
        onClick: prepareCompanionChat,
      },
      [
        ctx.UI.Icon({ name: "Chat", size: 20 }),
        ctx.UI.Text({
          text: hasExistingChat ? text.start : text.create,
        }),
      ],
    ),
  );

  if (autoEnabledState.value) {
    children.push(
      ctx.UI.OutlinedButton(
        {
          fillMaxWidth: true,
          enabled: !!book && !busyState.value,
          onClick: regenerateComments,
        },
        [
          ctx.UI.Icon({ name: "AutoMode", size: 20 }),
          ctx.UI.Text({ text: text.regenerate }),
        ],
      ),
    );
  }

  children.push(
    ctx.UI.Row(
      { fillMaxWidth: true, spacing: 10 },
      [
        ctx.UI.OutlinedButton(
          {
            weight: 1,
            enabled: !busyState.value,
            onClick: () => loadDashboard(true),
          },
          [
            ctx.UI.Icon({ name: "Refresh", size: 18 }),
            ctx.UI.Text({ text: text.refresh }),
          ],
        ),
        ctx.UI.OutlinedButton(
          {
            weight: 1,
            onClick: () => ctx.navigate("native.packages"),
          },
          [
            ctx.UI.Icon({ name: "Extension", size: 18 }),
            ctx.UI.Text({ text: text.manage }),
          ],
        ),
      ],
    ),
    ctx.UI.Text({
      text: text.hint,
      style: "bodySmall",
      color: colors.onSurfaceVariant,
    }),
  );

  return ctx.UI.LazyColumn(
    {
      onLoad: async () => {
        if (!initializedState.value) {
          initializedState.set(true);
          await loadDashboard(false);
        }
      },
      fillMaxSize: true,
      padding: 16,
      spacing: 14,
    },
    children,
  );
}

exports.default = readingCompanionEntryScreen;
exports.unwrapToolResult = unwrapToolResult;
exports.readChatMap = readChatMap;
