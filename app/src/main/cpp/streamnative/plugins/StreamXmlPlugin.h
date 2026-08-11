#pragma once

#include <string>

#include "../StreamKmpGraph.h"
#include "StreamPlugin.h"

namespace streamnative {

class StreamXmlPlugin final : public StreamPlugin {
public:
    explicit StreamXmlPlugin(bool includeTagsInOutput = true);

    PluginState state() const override;
    bool blocksCompetingPluginsWhileTrying() const override;
    bool processChar(char16_t c, bool atStartOfLine) override;
    bool initPlugin() override;
    void reset() override;

private:
    enum class StartState {
        WAIT_LT,
        WAIT_FIRST_LETTER,
        IN_TAG_NAME,
        IN_ATTRS,
    };

    enum class DisplayEndState {
        WAIT_LT,
        WAIT_SLASH,
        IN_NAME,
        IN_WHITESPACE,
    };

    bool includeTagsInOutput_;
    PluginState state_;
    StartState startState_;

    bool allowStartAfterEndTag_;
    bool allowStartAfterPunctuation_;

    std::u16string tagName_;
    std::u16string endPattern_;
    bool haveEndPattern_;
    KmpMatcher endMatcher_;
    bool displayEndMode_;
    bool displayThinkFamily_;
    DisplayEndState displayEndState_;
    std::u16string displayEndName_;
    char16_t startQuote_ = 0;
    char16_t startLastNonWhitespaceOutsideQuote_ = 0;
    char16_t lastChar_ = 0;

    bool handleDefaultCharacter(char16_t c);
    void updatePunctuationAllowance(char16_t c);
    bool processStartMatcher(char16_t c);
    void buildEndPattern();
    bool processDisplayEndMatcher(char16_t c);
    void restartDisplayEndMatcher(char16_t c);
    bool isValidDisplayEndName() const;
    bool isValidDisplayEndPrefix() const;

    static bool isAsciiLetter(char16_t c);
    static char16_t asciiLower(char16_t c);
    static bool isWhitespace(char16_t c);
    static bool isTagNameContinuationChar(char16_t c);
    static bool equalsAsciiIgnoreCase(const std::u16string& value, const char16_t* expected);
    static bool isDisplayTagName(const std::u16string& tagName);
    static bool isThinkFamilyTagName(const std::u16string& tagName);
    static bool isPunctuationTrigger(char16_t c);
    static bool isEmojiTrigger(char16_t c);
    static bool isEmojiContinuationChar(char16_t c);
};

} // namespace streamnative
