#include <iostream>
#include <string>

#include "streamnative/StreamOperators.h"
#include "streamnative/plugins/StreamXmlPlugin.h"

using streamnative::PluginState;
using streamnative::StreamXmlPlugin;

namespace {

PluginState feed(const std::u16string& input) {
    StreamXmlPlugin plugin(true);
    plugin.initPlugin();
    bool atStartOfLine = true;
    for (const char16_t character : input) {
        plugin.processChar(character, atStartOfLine);
        atStartOfLine = character == u'\n';
    }
    return plugin.state();
}

bool expectState(const std::u16string& input, PluginState expected, const char* label) {
    const PluginState actual = feed(input);
    if (actual == expected) return true;
    std::cerr << label << ": expected state " << static_cast<int>(expected)
              << ", got " << static_cast<int>(actual) << '\n';
    return false;
}

bool expectMarkdownSessionKeepsQuotedMarkdownInXml() {
    constexpr int mdHeader = 0;
    constexpr int mdXmlBlock = 8;
    const std::u16string input = u"<think title=\"x>\n# heading\n\">secret</think>answer";
    const int headingIndex = static_cast<int>(input.find(u'#'));
    auto* session = streamnative::createMarkdownBlockSession();
    const auto segments = streamnative::markdownSessionPush(
            session,
            reinterpret_cast<const jchar*>(input.data()),
            static_cast<int>(input.size()));
    streamnative::destroyMarkdownSession(session);

    bool headingIsXml = false;
    for (const auto& segment : segments) {
        if (segment.type == mdHeader) {
            std::cerr << "multiline quoted XML was claimed by Markdown header\n";
            return false;
        }
        if (segment.type == mdXmlBlock && segment.start <= headingIndex && headingIndex < segment.end) {
            headingIsXml = true;
        }
    }
    if (!headingIsXml) {
        std::cerr << "multiline quoted Markdown content was not emitted in the XML segment\n";
    }
    return headingIsXml;
}

} // namespace

int main() {
    bool ok = true;
    ok &= expectState(
            u"<thinking>draft</think><tool name=\"visit_web\"></tool>",
            PluginState::IDLE,
            "think-family closer");
    ok &= expectState(
            u"<THINK type=\"analysis\">draft</thinking\u2003\n><tool name=\"visit_web\"></tool>",
            PluginState::IDLE,
            "case-whitespace closer");
    ok &= expectState(
            u"<SEARCH source=\"web\">draft</search ><tool name=\"visit_web\"></tool>",
            PluginState::IDLE,
            "search closer");
    ok &= expectState(
            u"<think>draft</think bogus><tool name=\"visit_web\"></tool>",
            PluginState::PROCESSING,
            "malformed closer must fail closed");
    ok &= expectState(
            u"<THINK/><tool name=\"visit_web\">",
            PluginState::PROCESSING,
            "display self-closing boundary");
    ok &= expectState(
            u"<br/><tool name=\"visit_web\">",
            PluginState::IDLE,
            "generic self-closing boundary");
    ok &= expectState(
            u"<think title=\"x/>y\">",
            PluginState::PROCESSING,
            "quoted slash terminator is not self-closing");
    ok &= expectMarkdownSessionKeepsQuotedMarkdownInXml();
    return ok ? 0 : 1;
}
