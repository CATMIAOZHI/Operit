const SIDEBAR_ENTRY_ID = "reading_companion_sidebar";
const SIDEBAR_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_entry";
const HISTORY_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_history";
const DETAIL_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_run_detail";
const SUMMARIES_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_summaries";
const FILES_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_files";
const FILE_VIEW_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_file_view";
const readingCompanionEntryScreen = require(
  "./ui/reading_companion_entry/index.ui.js",
);
const readingCompanionHistoryScreen = require(
  "./ui/reading_companion_history/index.ui.js",
);
const readingCompanionRunDetailScreen = require(
  "./ui/reading_companion_run_detail/index.ui.js",
);
const readingCompanionSummariesScreen = require(
  "./ui/reading_companion_summaries/index.ui.js",
);
const readingCompanionFilesScreen = require(
  "./ui/reading_companion_files/index.ui.js",
);
const readingCompanionFileViewScreen = require(
  "./ui/reading_companion_file_view/index.ui.js",
);

function registerToolPkg() {
  ToolPkg.registerUiRoute({
    id: "reading_companion_entry",
    route: SIDEBAR_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionEntryScreen,
    params: {},
    keepAlive: true,
    title: {
      zh: "AI 阅读伴侣",
      en: "AI Reading Companion",
    },
  });
  ToolPkg.registerUiRoute({
    id: "reading_companion_history",
    route: HISTORY_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionHistoryScreen,
    params: {},
    title: {
      zh: "段评历史",
      en: "Commentary history",
    },
  });
  ToolPkg.registerUiRoute({
    id: "reading_companion_summaries",
    route: SUMMARIES_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionSummariesScreen,
    params: {},
    title: {
      zh: "章节摘要",
      en: "Chapter summaries",
    },
  });
  ToolPkg.registerUiRoute({
    id: "reading_companion_files",
    route: FILES_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionFilesScreen,
    params: {},
    title: {
      zh: "已保存书籍文件",
      en: "Saved book files",
    },
  });
  ToolPkg.registerUiRoute({
    id: "reading_companion_file_view",
    route: FILE_VIEW_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionFileViewScreen,
    params: {},
    title: {
      zh: "文件查看",
      en: "File view",
    },
  });
  ToolPkg.registerUiRoute({
    id: "reading_companion_run_detail",
    route: DETAIL_ROUTE,
    runtime: "compose_dsl",
    screen: readingCompanionRunDetailScreen,
    params: {},
    title: {
      zh: "段评任务详情",
      en: "Commentary run detail",
    },
  });
  ToolPkg.registerNavigationEntry({
    id: SIDEBAR_ENTRY_ID,
    route: SIDEBAR_ROUTE,
    surface: "main_sidebar_plugins",
    title: {
      zh: "AI 阅读伴侣",
      en: "AI Reading Companion",
    },
    icon: Icons.Book,
    order: 110,
  });
  return true;
}

exports.registerToolPkg = registerToolPkg;
