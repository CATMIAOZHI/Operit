const TOOL_PACKAGE = "reading_companion";
const AUTO_COMMENTARY_PACKAGE = "reading_companion_auto_commentary";
const HISTORY_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_history";

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
        "Use Legado reading tools in any ordinary chat, with plot reactions, recall, and optional AI paragraph commentary.",
      basicTitle: "Reading companion",
      basicOn: "Connected to Legado and available in ordinary chats when the topic is reading.",
      basicOff: "Turn this on before asking an ordinary chat about a Legado book.",
      autoTitle: "AI auto commentary",
      autoOn:
        "On. The selected character pre-generates comments for the next chapter. See the current setup below.",
      autoOff:
        "Off. Turn on to pre-generate next-chapter comments; this reads the next chapter and spends model tokens.",
      configTitle: "Current commentary setup",
      configCharacter: "Writer",
      configModel: "Model",
      configTrigger:
        "Trigger · about 20 seconds after reading progress changes, or manually below",
      configReading:
        "Reads · the complete next chapter plus up to 8 recent chapters / 48,000 characters, trimmed to the model window",
      configDensity:
        "Density · usually 0–3 comments; hard limit 6; silence is allowed",
      configDelivery:
        "Delivery · saved to Legado in advance and revealed only when the anchored paragraph is reached",
      modelSourceCaller: "calling ordinary chat model",
      modelSourceCharacter: "model fixed by the character card",
      modelSourceGlobal: "global dialogue model",
      modelSourceUnknown: "resolved when generation starts",
      changeModelHint:
        "Change the model in the character card binding or the global Dialogue model mapping.",
      personaTitle: "Commentary character",
      personaHint:
        "This character writes the comments and appears as their author in Legado. It does not change any chat character.",
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
      history: "View all records",
      auditTitle: "Audit chats",
      auditOpened: "Audit chat opened.",
      auditOpenFailed: "Could not open the audit chat: ",
      auditDeleteHint:
        "These chats are permanently hidden; they can only be viewed or deleted from the hidden chat list.",
      refresh: "Refresh status",
      manage: "Open package management",
      regenerate: "Generate next-chapter comments now",
      regenerating: "Generating comments with the selected model…",
      latestRun: "Latest commentary task",
      flowTitle: "Generation flow",
      flowStages: {
        reading_target: "Read current position and target chapter",
        preparing_context: "Prepare bounded prior context",
        resolving_model: "Resolve character card and model",
        waiting_model: "Send request and wait for the model",
        validating_response: "Validate anchors and comment format",
        saving_comments: "Save comments and notify Legado",
        completed: "Ready in Legado",
      },
      triggerManual: "Manual",
      triggerBackground: "After reading progress",
      durationLabel: "Duration",
      seconds: "s",
      inputLabel: "estimated input",
      targetLabel: "target chapter",
      contextLabel: "prior context",
      characters: "characters",
      contextWindowLabel: "model window",
      tokens: "tokens",
      stageTimeoutHint: "Timed out during: ",
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
        "Background history stays on this device in reading_companion.db. Run details show generated comment text and the real operation trace, and can open the full audit chat.",
      enableBasicFailed: "Could not change reading companion state: ",
      enableAutoFailed: "Could not change auto commentary state: ",
      enableStateMismatch: "The package did not turn on.",
      disableStateMismatch: "The package did not turn off.",
      regenerateFailed: "Could not generate next-chapter comments: ",
      loadFailed: "Status check failed: ",
      readyNotice: "Status refreshed.",
      generatedNotice: "The next chapter commentary task finished.",
      hint:
        "Enable the ToolPkg, then use any ordinary chat. Reading guidance appears only when the conversation is about books or reading; other chats are unchanged.",
    };
  }
  return {
    title: "AI 阅读伴侣",
    description:
      "开启后，任意普通对话都可在谈到阅读时调用 Legado 伴读工具，并按需开启 AI 自动段评。",
    basicTitle: "阅读伴侣",
    basicOn: "已连接工具能力；普通对话谈到阅读时即可使用。",
    basicOff: "请先开启，再在普通对话中询问 Legado 书籍。",
    autoTitle: "AI 自动段评",
    autoOn:
      "已开启。所选角色会预生成下一章段评；详细规则见下方“当前段评设置”。",
    autoOff:
      "已关闭。开启后会按需读取最新章节内容，产生模型 Token 消耗。",
    configTitle: "当前段评设置",
    configCharacter: "作者",
    configModel: "模型",
    configTrigger: "触发 · 阅读进度变化约 20 秒后自动生成，也可在下方手动生成",
    configReading:
      "读取 · 下一章全文 + 最近最多 8 章/4.8 万字前情，实际按模型窗口裁剪",
    configDensity: "频率 · 普通章节通常 0～3 条，硬上限 6 条，允许完全沉默",
    configDelivery: "展示 · 提前写入 Legado，读到对应段落时才显示",
    modelSourceCaller: "发起本次调用的普通对话模型",
    modelSourceCharacter: "角色卡固定模型",
    modelSourceGlobal: "全局“对话”模型",
    modelSourceUnknown: "生成时解析",
    changeModelHint: "需要换模型时，请修改角色卡的模型绑定或全局“对话”模型映射。",
    personaTitle: "段评角色",
    personaHint:
      "由这个角色写段评；Legado 会把角色卡名字显示为段评作者，不会修改任何聊天角色。",
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
    history: "查看全部记录",
    auditTitle: "审计对话",
    auditOpened: "已打开审计对话。",
    auditOpenFailed: "无法打开审计对话：",
    auditDeleteHint: "这些聊天永久隐藏，只能在隐藏聊天列表查看或删除。",
    refresh: "刷新状态",
    manage: "打开包管理",
    regenerate: "立即生成下一章段评",
    regenerating: "正在使用所选模型生成段评…",
    latestRun: "最近一次段评任务",
    flowTitle: "生成流程",
    flowStages: {
      reading_target: "读取当前进度与目标章节",
      preparing_context: "整理有限前情",
      resolving_model: "解析角色卡与模型",
      waiting_model: "发送请求并等待模型",
      validating_response: "校验段落锚点与格式",
      saving_comments: "保存段评并通知 Legado",
      completed: "已可在 Legado 中显示",
    },
    triggerManual: "手动触发",
    triggerBackground: "阅读进度触发",
    durationLabel: "耗时",
    seconds: "秒",
    inputLabel: "预估输入",
    targetLabel: "目标章",
    contextLabel: "前情",
    characters: "字",
    contextWindowLabel: "模型窗口",
    tokens: "Token",
    stageTimeoutHint: "超时发生阶段：",
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
    noValidComments: "模型未返回有效段评",
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
      no_valid_comments: "模型返回内容，但没有有效段评被接受。",
      role_not_selected: "请先为当前书籍选择段评角色。",
      role_unavailable: "所选段评角色已不存在，请重新选择。",
      already_generating: "已有段评任务正在生成中，请等待完成后再试。",
      cancelled: "任务被系统或用户取消。",
      interrupted: "任务进程中断。",
      unknown_error: "任务因未知原因失败。",
    },
    queuedHint:
      "后台历史保存在本机 reading_companion.db；详情可显示已生成段评全文与实际调用链，并可打开对应的审计对话。",
    enableBasicFailed: "切换阅读伴侣失败：",
    enableAutoFailed: "切换自动段评失败：",
    enableStateMismatch: "工具包未能开启。",
    disableStateMismatch: "工具包未能关闭。",
    regenerateFailed: "生成下一章段评失败：",
    loadFailed: "状态检查失败：",
    readyNotice: "状态已刷新。",
    generatedNotice: "下一章段评任务已完成。",
    hint:
      "开启工具包后，请在任意普通对话中继续使用；只有谈到书籍/阅读时才会引导伴读工具，其他聊天不受影响。",
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
    no_valid_comments: text.noValidComments,
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
    normalized === "no_valid_comments" ||
    normalized === "interrupted" ||
    normalized === "cancelled" ||
    normalized === "superseded"
  ) {
    return "error";
  }
  return "onSurfaceVariant";
}

function autoCommentErrorLabel(text, error, stage) {
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
    "模型返回内容，但没有有效段评被接受": "no_valid_comments",
    "任务被系统或用户取消": "cancelled",
    "任务进程中断": "interrupted",
    "未知错误": "unknown_error",
  };
  const code = legacyCodes[raw] || raw;
  const message = text.runErrors[code] || text.runErrors.unknown_error;
  const normalizedStage = String(stage || "").trim();
  return code === "model_timeout" && text.flowStages[normalizedStage]
    ? `${message} ${text.stageTimeoutHint}${text.flowStages[normalizedStage]}`
    : message;
}

function modelSourceLabel(text, source) {
  const labels = {
    caller_chat: text.modelSourceCaller,
    character_card: text.modelSourceCharacter,
    global_chat: text.modelSourceGlobal,
  };
  return labels[String(source || "").trim()] || text.modelSourceUnknown;
}

function runTriggerLabel(text, trigger) {
  return String(trigger || "").trim() === "manual"
    ? text.triggerManual
    : text.triggerBackground;
}

function formatDuration(text, durationMs) {
  const milliseconds = Number(durationMs || 0);
  if (!Number.isFinite(milliseconds) || milliseconds <= 0) {
    return "";
  }
  return `${Math.max(0.1, milliseconds / 1000).toFixed(
    milliseconds >= 10000 ? 0 : 1,
  )}${text.seconds}`;
}

function generationFlowRows(ctx, text, colors, run) {
  if (!run || !run.stage || run.stage === "starting") {
    return [];
  }
  const stages = Object.keys(text.flowStages);
  const currentStage = String(run.stage || "").trim();
  const currentIndex = Math.max(0, stages.indexOf(currentStage));
  const status = String(run.status || "").trim().toLowerCase();
  const succeeded =
    status === "generated" ||
    status === "cached" ||
    status === "no_next_chapter";
  const failed =
    status === "failed" ||
    status === "cancelled" ||
    status === "interrupted" ||
    status === "superseded";
  return stages.map((stage, index) => {
    const complete = succeeded || index < currentIndex;
    const current = !succeeded && index === currentIndex;
    const marker = complete ? "✓" : current ? (failed ? "!" : "●") : "○";
    const color = complete
      ? colors.primary
      : current && failed
        ? colors.error
        : current
          ? colors.tertiary
          : colors.onSurfaceVariant;
    return ctx.UI.Row(
      {
        fillMaxWidth: true,
        spacing: 8,
        verticalAlignment: "center",
      },
      [
        ctx.UI.Text({
          text: marker,
          color,
          fontWeight: current ? "bold" : "normal",
        }),
        ctx.UI.Text({
          text: text.flowStages[stage],
          style: "bodySmall",
          color,
          fontWeight: current ? "medium" : "normal",
        }),
      ],
    );
  });
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
  const historyState = useStateValue(ctx, "history", null);
  const auditGroupsState = useStateValue(ctx, "auditGroups", []);
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
      historyState.set(null);
      auditGroupsState.set([]);
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
        try {
          historyState.set(
            await callPackageTool(
              ctx,
              TOOL_PACKAGE,
              "auto_commentary_history",
              { limit: 10 },
            ),
          );
        } catch (error) {
          errors.push(toErrorText(error));
        }
        try {
          const auditResult = await callPackageTool(
            ctx,
            TOOL_PACKAGE,
            "list_audit_chats",
            {},
          );
          auditGroupsState.set(
            auditResult && Array.isArray(auditResult.groups)
              ? auditResult.groups
              : [],
          );
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
      const selected = await ctx.setReadingCompanionCommentaryCharacter({
        bookId,
        roleCardId: String(card.id).trim(),
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
      const queued = await callPackageTool(
        ctx,
        AUTO_COMMENTARY_PACKAGE,
        "queue_regenerate_next_chapter_comments",
        {},
      );
      if (queued && String(queued.status || "").trim() === "already_generating") {
        errorState.set(
          `${text.regenerateFailed}${text.runErrors.already_generating}`,
        );
        busyState.set(false);
        busyLabelState.set("");
        return;
      }
      const queuedAt = Number(queued && queued.queuedAt || Date.now());
      let completedRun = null;
      for (let attempt = 0; attempt < 190; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 900));
        const status = await callPackageTool(
          ctx,
          AUTO_COMMENTARY_PACKAGE,
          "auto_commentary_status",
          {},
        );
        autoStatusState.set(status);
        const run =
          status && status.latestRun && typeof status.latestRun === "object"
            ? status.latestRun
            : null;
        const isThisRequest =
          !!run &&
          String(run.trigger || "").trim() === "manual" &&
          Number(run.startedAt || 0) >= queuedAt - 2000;
        const runStatus = String(run && run.status || "").trim().toLowerCase();
        if (
          isThisRequest &&
          runStatus !== "generating" &&
          runStatus !== "running"
        ) {
          completedRun = run;
          break;
        }
      }
      if (!completedRun) {
        throw new Error(text.runErrors.model_timeout);
      }
      const completedStatus = String(completedRun.status || "").toLowerCase();
      if (
        completedStatus === "generated" ||
        completedStatus === "cached" ||
        completedStatus === "no_next_chapter"
      ) {
        noticeState.set(text.generatedNotice);
      } else {
        errorState.set(
          `${text.regenerateFailed}${autoCommentErrorLabel(
            text,
            completedRun.error,
            completedRun.stage,
          )}`,
        );
      }
    } catch (error) {
      errorState.set(`${text.regenerateFailed}${toErrorText(error)}`);
    } finally {
      busyState.set(false);
      busyLabelState.set("");
    }
  };

  const openHistory = async () => {
    if (!basicEnabledState.value) {
      return;
    }
    await Promise.resolve(ctx.navigate(HISTORY_ROUTE));
  };

  const openAuditChatByRunId = async (runId) => {
    const normalizedRunId = Number(runId || 0);
    if (!Number.isFinite(normalizedRunId) || normalizedRunId <= 0) {
      return;
    }
    if (!ctx.openReadingAuditChat) {
      errorState.set(
        `${text.auditOpenFailed}${useEnglish ? "unavailable" : "当前版本不支持"}`,
      );
      return;
    }
    noticeState.set("");
    errorState.set("");
    try {
      await ctx.openReadingAuditChat(normalizedRunId);
      noticeState.set(text.auditOpened);
    } catch (openError) {
      errorState.set(`${text.auditOpenFailed}${toErrorText(openError)}`);
    }
  };

  const book = readingState.value;
  const latestRunFromStatus =
    autoStatusState.value &&
    autoStatusState.value.latestRun &&
    typeof autoStatusState.value.latestRun === "object"
      ? autoStatusState.value.latestRun
      : null;
  const latestRunFromHistory =
    historyState.value &&
    Array.isArray(historyState.value.runs) &&
    historyState.value.runs.length > 0 &&
    typeof historyState.value.runs[0] === "object"
      ? historyState.value.runs[0]
      : null;
  const latestRun = latestRunFromStatus || latestRunFromHistory;
  const commentaryConfiguration =
    autoStatusState.value &&
    autoStatusState.value.configuration &&
    typeof autoStatusState.value.configuration === "object"
      ? autoStatusState.value.configuration
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

  if (autoEnabledState.value && book) {
    const configuredModel = commentaryConfiguration
      ? `${modelSourceLabel(
          text,
          commentaryConfiguration.modelSource,
        )} · ${String(commentaryConfiguration.modelConfigName || "")} / ${String(
          commentaryConfiguration.model || "",
        )}`
      : text.modelSourceUnknown;
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.secondaryContainer,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          ctx.UI.Text({
            text: text.configTitle,
            style: "titleMedium",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: `${text.configCharacter} · ${
              selectedRoleCardName || text.personaSelect
            }`,
            style: "bodyMedium",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: `${text.configModel} · ${configuredModel}`,
            style: "bodyMedium",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: text.configTrigger,
            style: "bodySmall",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: text.configReading,
            style: "bodySmall",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: text.configDensity,
            style: "bodySmall",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: text.configDelivery,
            style: "bodySmall",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: text.changeModelHint,
            style: "labelSmall",
            color: colors.onSecondaryContainer,
          }),
        ]),
      ),
    );
  }

  if (autoEnabledState.value || latestRun) {
    const flowRows = generationFlowRows(ctx, text, colors, latestRun);
    const durationText = latestRun
      ? formatDuration(text, latestRun.durationMs)
      : "";
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
                text: `${text.configCharacter} · ${String(
                  latestRun.roleCardName,
                )}\n${text.configModel} · ${String(
                  latestRun.modelConfigName ||
                    latestRun.model ||
                    latestRun.provider ||
                    "",
                )}${
                  latestRun.model &&
                  latestRun.model !== latestRun.modelConfigName
                    ? ` / ${String(latestRun.model)}`
                    : ""
                }`,
                style: "bodySmall",
                color: colors.onSurfaceVariant,
              })
            : ctx.UI.Spacer({ height: 0 }),
          latestRun
            ? ctx.UI.Text({
                text: `${runTriggerLabel(text, latestRun.trigger)}${
                  durationText ? ` · ${text.durationLabel} ${durationText}` : ""
                }`,
                style: "bodySmall",
                color: colors.onSurfaceVariant,
              })
            : ctx.UI.Spacer({ height: 0 }),
          latestRun && Number(latestRun.targetCharacterCount || 0) > 0
            ? ctx.UI.Text({
                text: `${text.targetLabel} ${Number(
                  latestRun.targetCharacterCount,
                )} ${text.characters} · ${text.contextLabel} ${Number(
                  latestRun.contextChapterCount || 0,
                )} 章 / ${Number(
                  latestRun.contextCharacterCount || 0,
                )} ${text.characters}`,
                style: "bodySmall",
                color: colors.onSurfaceVariant,
              })
            : ctx.UI.Spacer({ height: 0 }),
          latestRun && Number(latestRun.estimatedInputTokens || 0) > 0
            ? ctx.UI.Text({
                text: `${text.inputLabel} ${Number(
                  latestRun.estimatedInputTokens,
                )} ${text.tokens} · ${text.contextWindowLabel} ${Number(
                  latestRun.contextWindowTokens || 0,
                )} ${text.tokens}`,
                style: "bodySmall",
                color: colors.onSurfaceVariant,
              })
            : ctx.UI.Spacer({ height: 0 }),
          latestRun && latestRun.error
            ? ctx.UI.Text({
                text: autoCommentErrorLabel(
                  text,
                  latestRun.error,
                  latestRun.stage,
                ),
                style: "bodySmall",
                color: colors.error,
              })
            : ctx.UI.Spacer({ height: 0 }),
          flowRows.length > 0
            ? ctx.UI.Text({
                text: text.flowTitle,
                style: "labelLarge",
                color: colors.onSurface,
              })
            : ctx.UI.Spacer({ height: 0 }),
          ...flowRows,
          ctx.UI.Text({
            text: text.queuedHint,
            style: "bodySmall",
            color: colors.onSurfaceVariant,
          }),
          ctx.UI.OutlinedButton(
            {
              fillMaxWidth: true,
              enabled: !busyState.value,
              onClick: openHistory,
            },
            [
              ctx.UI.Icon({ name: "History", size: 18 }),
              ctx.UI.Text({ text: text.history }),
            ],
          ),
        ]),
      ),
    );
  }

  const auditGroups = auditGroupsState.value;
  if (auditGroups.length > 0) {
    children.push(
      ctx.UI.Card(
        { fillMaxWidth: true, containerColor: colors.surface },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          ctx.UI.Text({
            text: text.auditTitle,
            style: "titleMedium",
            color: colors.onSurface,
          }),
          ...auditGroups.map((group) => {
            const groupBook = String(
              (group && (group.bookName || group.bookId)) || "",
            ).trim();
            const root =
              group && group.root && typeof group.root === "object"
                ? group.root
                : null;
            const groupChats =
              group && Array.isArray(group.chats) ? group.chats : [];
            return ctx.UI.Column({ fillMaxWidth: true, spacing: 4 }, [
              ctx.UI.Text({
                text: groupBook || (useEnglish ? "Book" : "书籍"),
                style: "labelLarge",
                color: colors.onSurface,
              }),
              root && root.chatId
                ? ctx.UI.Text({
                    text:
                      (useEnglish
                        ? "Audit parent · "
                        : "审计父聊天 · ") + (root.title || root.chatId),
                    style: "bodySmall",
                    color: colors.onSurfaceVariant,
                    maxLines: 1,
                    overflow: "ellipsis",
                  })
                : ctx.UI.Spacer({ height: 0 }),
              ...groupChats.map((chat) =>
                chat && chat.chatId && chat.runId
                  ? ctx.UI.Row(
                      {
                        fillMaxWidth: true,
                        spacing: 8,
                        verticalAlignment: "center",
                        modifier: ctx.Modifier
                          .fillMaxWidth()
                          .clickable(() => openAuditChatByRunId(chat.runId)),
                      },
                      [
                        ctx.UI.Column({ weight: 1, spacing: 2 }, [
                          ctx.UI.Text({
                            text: String(chat.title || "").trim() ||
                              (useEnglish ? "Run chat" : "任务聊天"),
                            style: "bodyMedium",
                            color: colors.onSurface,
                            maxLines: 1,
                            overflow: "ellipsis",
                          }),
                          ctx.UI.Text({
                            text:
                              `${statusLabel(text, chat.status)} · ` +
                              runTriggerLabel(text, chat.trigger),
                            style: "bodySmall",
                            color: colors.onSurfaceVariant,
                          }),
                        ]),
                        ctx.UI.Text({
                          text: useEnglish ? "Open ›" : "打开 ›",
                          style: "labelMedium",
                          color: colors.primary,
                        }),
                      ],
                    )
                  : ctx.UI.Spacer({ height: 0 }),
              ),
            ]);
          }),
          ctx.UI.Text({
            text: text.auditDeleteHint,
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
