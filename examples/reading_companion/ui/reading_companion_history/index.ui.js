const {
  DETAIL_ROUTE,
  RUN_ID_ENV_KEY,
  callHistoryTool,
  formatDate,
  formatDuration,
  statusColor,
  statusLabel,
  toErrorText,
  triggerLabel,
  useEnglishLocale,
  unwrapToolResult,
} = require("../history_shared.js");

function historyScreen(ctx) {
  const english = useEnglishLocale();
  const { UI } = ctx;
  const colors = ctx.MaterialTheme.colorScheme;
  const [initialized, setInitialized] = ctx.useState(
    "historyInitialized",
    false,
  );
  const [loading, setLoading] = ctx.useState("historyLoading", true);
  const [error, setError] = ctx.useState("historyError", "");
  const [tasks, setTasks] = ctx.useState("historyTasks", []);
  const [runs, setRuns] = ctx.useState("historyRuns", []);

  const load = async () => {
    setLoading(true);
    setError("");
    try {
      const result = await callHistoryTool(ctx, "auto_commentary_history", {
        limit: 50,
      });
      setRuns(Array.isArray(result && result.runs) ? result.runs : []);
      const taskName = ctx.resolveToolName ? await ctx.resolveToolName({ packageName: "reading_companion_tasks", toolName: "list_tasks", preferImported: true }) : "reading_companion_tasks:list_tasks";
      const taskResult = unwrapToolResult(await ctx.callTool(taskName, { limit: 20 }));
      setTasks(Array.isArray(taskResult.tasks) ? taskResult.tasks : []);
    } catch (loadError) {
      setError(toErrorText(loadError));
    } finally {
      setLoading(false);
    }
  };

  const openRun = async (run) => {
    const runId = Number(run && run.runId || 0);
    if (!Number.isFinite(runId) || runId <= 0) {
      return;
    }
    await Promise.resolve(ctx.setEnv(RUN_ID_ENV_KEY, String(runId)));
    await Promise.resolve(ctx.navigate(DETAIL_ROUTE));
  };

  const title = english ? "Commentary history" : "段评历史";
  const empty = english ? "No commentary task records yet." : "还没有段评任务记录。";
  const errorTitle = english ? "Could not load history" : "加载历史失败";
  const refresh = english ? "Refresh" : "刷新";

  const children = [];
  for (const task of tasks) {
    children.push(UI.Card({ fillMaxWidth: true }, UI.Column({ padding: 12, spacing: 6 }, [
      UI.Text({ text: `${task.kind === "summary" ? (english ? "Summary" : "摘要") : (english ? "Commentary" : "段评")} · ${({
        queued: english ? "Queued" : "等待中",
        running: english ? "Running" : "生成中",
        cancelling: english ? "Cancelling" : "取消中",
        cancelled: english ? "Cancelled" : "已取消",
        interrupted: english ? "Interrupted" : "已中断",
        completed: english ? "Completed" : "已完成",
        completed_with_failures: english ? "Partially completed" : "部分完成",
        failed: english ? "Failed" : "失败",
      })[task.status] || task.status}`, style: "titleSmall" }),
      UI.Text({ text: String(task.task_id), style: "bodySmall" }),
      UI.Text({ text: `${english ? "Completed" : "已完成"}: ${Number((task.result || task.progress || {}).completedCount || 0)}` }),
      ["queued", "running", "cancelling"].includes(task.status) ? UI.OutlinedButton({ onClick: async () => {
        try {
          const name = ctx.resolveToolName ? await ctx.resolveToolName({ packageName: "reading_companion_tasks", toolName: "cancel_task", preferImported: true }) : "reading_companion_tasks:cancel_task";
          unwrapToolResult(await ctx.callTool(name, { task_id: task.task_id }));
          await load();
        } catch (cancelError) {
          setError(toErrorText(cancelError));
        }
      } }, UI.Text({ text: english ? "Cancel task" : "取消任务" })) : null,
    ].filter(Boolean))));
  }
  if (loading) {
    children.push(
      UI.Row(
        {
          fillMaxWidth: true,
          spacing: 10,
          verticalAlignment: "center",
          padding: 16,
        },
        [
          UI.CircularProgressIndicator({ width: 20, height: 20, strokeWidth: 2 }),
          UI.Text({
            text: english ? "Loading records…" : "正在加载记录…",
            color: colors.onSurfaceVariant,
          }),
        ],
      ),
    );
  } else if (error) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.errorContainer },
        UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
          UI.Text({ text: errorTitle, style: "titleMedium", color: colors.onErrorContainer }),
          UI.Text({ text: error, style: "bodySmall", color: colors.onErrorContainer }),
          UI.OutlinedButton({ onClick: load }, UI.Text({ text: refresh })),
        ]),
      ),
    );
  } else if (runs.length === 0) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.surfaceVariant },
        UI.Text({ text: empty, color: colors.onSurfaceVariant, padding: 16 }),
      ),
    );
  } else {
    runs.forEach((run) => {
      const runStatus = String(run && run.status || "").trim();
      const metadata = [
        triggerLabel(run && run.trigger, english),
        formatDate(run && run.startedAt, english),
      ];
      if (Number(run && run.durationMs || 0) > 0) {
        metadata.push(formatDuration(run.durationMs, english));
      }
      if (Number(run && run.commentCount || 0) > 0) {
        metadata.push(
          english
            ? `${run.commentCount} comments`
            : `${run.commentCount} 条段评`,
        );
      }
      children.push(
        UI.Card(
          {
            key: `run_${String(run && run.runId || "")}`,
            fillMaxWidth: true,
            containerColor: colors.surface,
            modifier: ctx.Modifier.fillMaxWidth().clickable(() => openRun(run)),
          },
          UI.Column({ fillMaxWidth: true, padding: 16, spacing: 6 }, [
            UI.Row(
              { fillMaxWidth: true, horizontalArrangement: "spaceBetween", verticalAlignment: "center" },
              [
                UI.Column({ weight: 1, spacing: 2 }, [
                  UI.Text({
                    text: String(run && run.bookName || "") ||
                      (english ? "Legado book" : "Legado 书籍"),
                    style: "titleMedium",
                    color: colors.onSurface,
                    maxLines: 1,
                    overflow: "ellipsis",
                  }),
                  UI.Text({
                    text: run && run.chapterNumber
                      ? english
                        ? `Target chapter ${run.chapterNumber}`
                        : `目标第 ${run.chapterNumber} 章`
                      : (english ? "Target unavailable" : "目标章节未知"),
                    style: "bodySmall",
                    color: colors.onSurfaceVariant,
                  }),
                ]),
                UI.Text({
                  text: statusLabel(runStatus, english),
                  style: "labelLarge",
                  color: colors[statusColor(runStatus)] || colors.onSurfaceVariant,
                }),
              ],
            ),
            UI.Text({
              text: metadata.join(" · "),
              style: "bodySmall",
              color: colors.onSurfaceVariant,
              maxLines: 2,
              overflow: "ellipsis",
            }),
            UI.Text({
              text: english ? "Open details ›" : "查看详情 ›",
              style: "labelMedium",
              color: colors.primary,
            }),
          ]),
        ),
      );
    });
  }

  return UI.Box(
    {
      fillMaxSize: true,
      topBarTitle: UI.Text({ text: title, maxLines: 1, overflow: "ellipsis" }),
      onLoad: async () => {
        if (!initialized) {
          setInitialized(true);
          await load();
        }
      },
    },
    UI.LazyColumn({
      fillMaxSize: true,
      padding: 16,
      spacing: 12,
    }, children),
  );
}

module.exports = historyScreen;
module.exports.default = historyScreen;
