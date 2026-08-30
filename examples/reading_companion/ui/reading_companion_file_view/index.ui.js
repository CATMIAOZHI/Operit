const {
  FILE_VIEW_NAME_ENV_KEY,
  FILE_VIEW_PATH_ENV_KEY,
  FILE_VIEW_READONLY_ENV_KEY,
  FILE_VIEW_RELATIVE_ENV_KEY,
  FILES_ROUTE,
  callHistoryTool,
  callWithTimeout,
  toErrorText,
  useEnglishLocale,
} = require("../history_shared.js");

function prettifyContent(name, content) {
  const text = String(content || "");
  if (!/\.json$/i.test(String(name || ""))) {
    return text;
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch (_error) {
    return text;
  }
}

function fileViewScreen(ctx) {
  const english = useEnglishLocale();
  const { UI } = ctx;
  const colors = ctx.MaterialTheme.colorScheme;
  const [initialized, setInitialized] = ctx.useState("fileViewInitialized", false);
  const [loading, setLoading] = ctx.useState("fileViewLoading", true);
  const [error, setError] = ctx.useState("fileViewError", "");
  const [content, setContent] = ctx.useState("fileViewContent", "");
  const [fontLevel, setFontLevel] = ctx.useState("fileViewFontLevel", 2);

  const path = String(ctx.getEnv(FILE_VIEW_PATH_ENV_KEY) || "").trim();
  const name = String(ctx.getEnv(FILE_VIEW_NAME_ENV_KEY) || "").trim() || path.split("/").pop();
  const relativePath = String(ctx.getEnv(FILE_VIEW_RELATIVE_ENV_KEY) || "").trim();
  const readOnly = String(ctx.getEnv(FILE_VIEW_READONLY_ENV_KEY) || "true") !== "false";

  const load = async () => {
    if (!path) {
      setError(english ? "No file was selected." : "未选择要查看的文件。");
      setLoading(false);
      return;
    }
    setLoading(true);
    setError("");
    try {
      const result = await callWithTimeout(
        () => callHistoryTool(ctx, "read_persisted_file", { path }),
        english ? "Reading timed out" : "读取超时",
      );
      setContent(
        prettifyContent(
          name,
          String(result && (result.content || result.text) || ""),
        ),
      );
    } catch (loadError) {
      setError(toErrorText(loadError));
    } finally {
      setLoading(false);
    }
  };

  const openInOperitFileManager = async () => {
    await Promise.resolve(ctx.navigate("native.file_manager"));
  };

  const FONT_STYLES = [
    "bodySmall",
    "bodyMedium",
    "bodyLarge",
    "headlineSmall",
  ];

  const children = [];
  children.push(
    UI.Row(
      {
        fillMaxWidth: true,
        horizontalArrangement: "spaceBetween",
        verticalAlignment: "center",
        padding: { start: 16, end: 16, top: 4 },
      },
      [
        UI.Column({ weight: 1, spacing: 2 }, [
          UI.Text({
            text: name || (english ? "File" : "文件"),
            style: "titleMedium",
            color: colors.onSurface,
            maxLines: 1,
            overflow: "ellipsis",
          }),
          relativePath
            ? UI.Text({
                text: relativePath,
                style: "labelSmall",
                color: colors.onSurfaceVariant,
                maxLines: 1,
                overflow: "ellipsis",
              })
            : null,
        ]),
        UI.Text({
          text: readOnly
            ? (english ? "Read-only" : "只读")
            : (english ? "Editable" : "可编辑"),
          style: "labelMedium",
          color: readOnly ? colors.onSurfaceVariant : colors.primary,
        }),
      ],
    ),
  );

  if (loading) {
    children.push(
      UI.Row(
        { fillMaxWidth: true, padding: 16, spacing: 10, verticalAlignment: "center" },
        [
          UI.CircularProgressIndicator({ width: 20, height: 20, strokeWidth: 2 }),
          UI.Text({
            text: english ? "Reading file…" : "正在读取文件…",
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
          UI.Text({ text: error, color: colors.onErrorContainer }),
          UI.OutlinedButton(
            { onClick: load },
            UI.Text({ text: english ? "Retry" : "重试" }),
          ),
        ]),
      ),
    );
  } else {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.surface },
        UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
          UI.Text({
            text:
              content ||
              (english ? "(empty file)" : "（空文件）"),
            style:
              FONT_STYLES[
                Math.max(0, Math.min(FONT_STYLES.length - 1, Number(fontLevel) || 2))
              ] || "bodyLarge",
            color: colors.onSurface,
          }),
        ]),
      ),
    );
  }

  children.push(
    UI.Row({ fillMaxWidth: true, spacing: 10, padding: { start: 16, end: 16 } }, [
      UI.OutlinedButton(
        {
          weight: 1,
          enabled: !loading,
          onClick: () => {
            setFontLevel(Math.max(0, (Number(fontLevel) || 2) - 1));
          },
        },
        UI.Text({ text: "A-" }),
      ),
      UI.OutlinedButton(
        {
          weight: 1,
          enabled: !loading,
          onClick: () => {
            setFontLevel(
              Math.min(
                FONT_STYLES.length - 1,
                (Number(fontLevel) || 2) + 1,
              ),
            );
          },
        },
        UI.Text({ text: "A+" }),
      ),
      UI.OutlinedButton(
        {
          weight: 2,
          onClick: openInOperitFileManager,
        },
        UI.Text({
          text: english ? "Open in Operit file manager" : "在 Operit 文件管理器打开",
          maxLines: 1,
        }),
      ),
    ]),
  );

  return UI.Box(
    {
      fillMaxSize: true,
      onLoad: async () => {
        if (!initialized) {
          setInitialized(true);
          await load();
        }
      },
    },
    UI.LazyColumn({ fillMaxSize: true, spacing: 10, padding: 10 }, children),
  );
}

module.exports = fileViewScreen;
module.exports.default = fileViewScreen;
