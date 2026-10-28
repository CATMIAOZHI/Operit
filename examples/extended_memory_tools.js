/* METADATA
{
    "name": "extended_memory_tools",

    "display_name": {
        "zh": "旧记忆查询（只读）",
        "en": "Legacy memory · Read-only search"
    },
    "description": {
        "zh": "只读查询原有记忆图谱。旧记忆不再新增、修改或删除；要记住新东西，请用新记忆工具包（memory_review）。",
        "en": "Read-only search of the old memory graph. It is no longer added to, changed or deleted; to remember something new, use the new memory package (memory_review)."
    },
    "category": "Memory",
    "enabledByDefault": true,
    "tools": [
        {
            "name": "query_memory",
            "description": {"zh": "搜索旧记忆图谱。", "en": "Search the legacy memory graph."},
            "parameters": [
                {"name": "query", "description": {"zh": "自然语言或关键词，| 分隔多个关键词，* 模糊匹配；单独 * 返回全部", "en": "Natural language or keywords; | separates keywords and * matches fuzzily; * alone returns all"}, "type": "string", "required": true},
                {"name": "folder_path", "description": {"zh": "限定文件夹", "en": "Folder filter"}, "type": "string", "required": false},
                {"name": "start_time", "description": {"zh": "创建时间下限，本地 YYYY-MM-DD 或 YYYY-MM-DD HH:mm", "en": "Created since, local YYYY-MM-DD or YYYY-MM-DD HH:mm"}, "type": "string", "required": false},
                {"name": "end_time", "description": {"zh": "创建时间上限，同 start_time 格式", "en": "Created until, same format as start_time"}, "type": "string", "required": false},
                {"name": "snapshot_id", "description": {"zh": "复用快照排除已返回记忆，省略则新建", "en": "Reuse a snapshot to exclude previously returned memories; omit to create one"}, "type": "string", "required": false},
                {"name": "threshold", "description": {"zh": "最低相关度，默认0", "en": "Minimum relevance, default 0"}, "type": "number", "required": false},
                {"name": "limit", "description": {"zh": "返回数量，默认20", "en": "Result limit, default 20"}, "type": "number", "required": false}
            ]
        },
        {
            "name": "get_memory_by_title",
            "description": {"zh": "按标题读取旧记忆。", "en": "Read a legacy memory by title."},
            "parameters": [
                {"name": "title", "description": {"zh": "标题", "en": "Title"}, "type": "string", "required": true},
                {"name": "chunk_index", "description": {"zh": "分块序号，从1开始", "en": "Chunk number starting at 1"}, "type": "number", "required": false},
                {"name": "chunk_range", "description": {"zh": "分块范围，例如3-7", "en": "Chunk range, e.g. 3-7"}, "type": "string", "required": false},
                {"name": "query", "description": {"zh": "可选的文档内搜索", "en": "Optional search within the document"}, "type": "string", "required": false},
                {"name": "limit", "description": {"zh": "结果上限", "en": "Result limit"}, "type": "number", "required": false}
            ]
        },
        {
            "name": "query_memory_links",
            "description": { "zh": "查询记忆链接（可按 ID/源标题/目标标题/关系类型过滤）。", "en": "Query memory links (filter by id/source/target/type)." },
            "parameters": [
                { "name": "link_id", "description": { "zh": "可选：链接ID", "en": "Optional: link id" }, "type": "number", "required": false },
                { "name": "source_title", "description": { "zh": "可选：源记忆标题", "en": "Optional: source memory title" }, "type": "string", "required": false },
                { "name": "target_title", "description": { "zh": "可选：目标记忆标题", "en": "Optional: target memory title" }, "type": "string", "required": false },
                { "name": "link_type", "description": { "zh": "可选：关系类型", "en": "Optional: link type" }, "type": "string", "required": false },
                { "name": "limit", "description": { "zh": "可选：返回上限 1-200，默认20", "en": "Optional: limit 1-200, default 20" }, "type": "number", "required": false }
            ]
        }
    ]
}*/
const LegacyMemoryQuery = (function () {
    function resolveCallerCardId() {
        if (typeof getCallerCardId !== 'function') {
            return undefined;
        }
        const callerCardId = String(getCallerCardId() || '').trim();
        return callerCardId || undefined;
    }
    async function query_memory_links(params) {
        try {
            const result = await Tools.Memory.queryLinks({
                linkId: params.link_id,
                sourceTitle: params.source_title,
                targetTitle: params.target_title,
                linkType: params.link_type,
                limit: params.limit,
                callerCardId: resolveCallerCardId(),
            });
            complete({ success: !!result, message: '记忆链接查询完成', data: result });
        }
        catch (error) {
            complete({ success: false, message: `查询记忆链接失败: ${error?.message ?? error}` });
        }
    }
    async function main() {
        complete({
            success: true,
            message: "旧记忆查询（只读）已加载：可用 query_memory、get_memory_by_title、query_memory_links；旧记忆不再新增或修改，新内容请用 memory_review。",
        });
    }
    return { query_memory_links, main };
})();
exports.query_memory_links = LegacyMemoryQuery.query_memory_links;
exports.main = LegacyMemoryQuery.main;
exports.query_memory = async (params) => complete(await toolCall({
    name: "query_memory", params: { ...params, caller_card_id: getCallerCardId(), __operit_package_chat_id: getChatId() || '' }
}));
exports.get_memory_by_title = async (params) => complete(await toolCall({
    name: "get_memory_by_title", params: { ...params, caller_card_id: getCallerCardId(), __operit_package_chat_id: getChatId() || '' }
}));
