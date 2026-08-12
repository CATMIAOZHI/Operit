#include "StreamXmlPlugin.h"

namespace streamnative {

StreamXmlPlugin::StreamXmlPlugin(bool includeTagsInOutput)
        : includeTagsInOutput_(includeTagsInOutput),
          state_(PluginState::IDLE),
          startState_(StartState::WAIT_LT),
          allowStartAfterEndTag_(false),
          allowStartAfterPunctuation_(false),
          haveEndPattern_(false),
          displayEndMode_(false),
          displayEndState_(DisplayEndState::WAIT_LT),
          displayCandidateClosing_(false),
          displayCandidateQuote_(0),
          displayLastNonWhitespace_(0) {
    reset();
}

PluginState StreamXmlPlugin::state() const {
    return state_;
}

bool StreamXmlPlugin::blocksCompetingPluginsWhileTrying() const {
    return state_ == PluginState::TRYING && startQuote_ != 0;
}

bool StreamXmlPlugin::initPlugin() {
    reset();
    return true;
}

void StreamXmlPlugin::reset() {
    state_ = PluginState::IDLE;
    startState_ = StartState::WAIT_LT;
    tagName_.clear();
    endMatcher_.reset();
    endPattern_.clear();
    haveEndPattern_ = false;
    displayEndMode_ = false;
    resetDisplayEndCandidate();
    displayFamilyStack_.clear();
    startQuote_ = 0;
    startLastNonWhitespace_ = 0;
    lastChar_ = 0;
}

bool StreamXmlPlugin::isAsciiLetter(char16_t c) {
    return (c >= u'A' && c <= u'Z') || (c >= u'a' && c <= u'z');
}

char16_t StreamXmlPlugin::asciiLower(char16_t c) {
    return (c >= u'A' && c <= u'Z') ? static_cast<char16_t>(c - u'A' + u'a') : c;
}

bool StreamXmlPlugin::isWhitespace(char16_t c) {
    return (c >= u'\t' && c <= u'\r') || (c >= u'\u001C' && c <= u'\u001F') ||
           c == u' ' || c == u'\u00A0' || c == u'\u1680' ||
           (c >= u'\u2000' && c <= u'\u200A') || c == u'\u2028' || c == u'\u2029' ||
           c == u'\u202F' || c == u'\u205F' || c == u'\u3000';
}

bool StreamXmlPlugin::equalsAsciiIgnoreCase(
        const std::u16string& value,
        const char16_t* expected) {
    size_t index = 0;
    while (expected[index] != 0) {
        if (index >= value.size() || asciiLower(value[index]) != expected[index]) {
            return false;
        }
        index++;
    }
    return index == value.size();
}

bool StreamXmlPlugin::isThinkFamilyTagName(const std::u16string& tagName) {
    return equalsAsciiIgnoreCase(tagName, u"think") ||
           equalsAsciiIgnoreCase(tagName, u"thinking");
}

bool StreamXmlPlugin::isDisplayTagName(const std::u16string& tagName) {
    return isThinkFamilyTagName(tagName) || equalsAsciiIgnoreCase(tagName, u"search");
}

bool StreamXmlPlugin::isTagNameContinuationChar(char16_t c) {
    return isAsciiLetter(c) || (c >= u'0' && c <= u'9') || c == u'_' ||
           c == u'-' || c == u'.' || c == u':';
}

bool StreamXmlPlugin::isPunctuationTrigger(char16_t c) {
    switch (c) {
        case u'\uFF0C': // ，
        case u'\u3002': // 。
        case u'\uFF1F': // ？
        case u'\uFF01': // ！
        case u'\uFF1A': // ：
        case u'\uFF08': // （
        case u'\uFF09': // ）
        case u'\u3010': // 【
        case u'\u3011': // 】
        case u'\u300A': // 《
        case u'\u300B': // 》
        case u':':
        case u',':
        case u'.':
        case u'?':
        case u'!':
        case u'~':
        case u'\uFF5E': // ～
            return true;
        default:
            return false;
    }
}

bool StreamXmlPlugin::isEmojiTrigger(char16_t c) {
    // Most modern emojis are surrogate pairs in UTF-16.
    if (c >= u'\xD800' && c <= u'\xDFFF') {
        return true;
    }

    // Common BMP emoji/symbol blocks (e.g. ☀, ❤, ✨, etc.).
    if ((c >= u'\x2300' && c <= u'\x23FF') ||
        (c >= u'\x2600' && c <= u'\x27BF') ||
        (c >= u'\x2B00' && c <= u'\x2BFF')) {
        return true;
    }

    return false;
}

bool StreamXmlPlugin::isEmojiContinuationChar(char16_t c) {
    switch (c) {
        case u'\u200D': // ZERO WIDTH JOINER
        case u'\uFE0E': // text presentation selector
        case u'\uFE0F': // emoji presentation selector
        case u'\u20E3': // combining enclosing keycap
            return true;
        default:
            return false;
    }
}

bool StreamXmlPlugin::handleDefaultCharacter(char16_t c) {
    updatePunctuationAllowance(c);
    return true;
}

void StreamXmlPlugin::updatePunctuationAllowance(char16_t c) {
    if (isPunctuationTrigger(c) || isEmojiTrigger(c)) {
        allowStartAfterPunctuation_ = true;
    } else if (c == u' ' || c == u'\t' || isEmojiContinuationChar(c)) {
        // keep
    } else {
        allowStartAfterPunctuation_ = false;
    }
}

bool StreamXmlPlugin::processStartMatcher(char16_t c) {
    switch (startState_) {
        case StartState::WAIT_LT: {
            if (c == u'<') {
                tagName_.clear();
                startQuote_ = 0;
                startLastNonWhitespace_ = 0;
                startState_ = StartState::WAIT_FIRST_LETTER;
                state_ = PluginState::TRYING;
            }
            return false;
        }
        case StartState::WAIT_FIRST_LETTER: {
            if (isAsciiLetter(c)) {
                tagName_.push_back(c);
                startState_ = StartState::IN_TAG_NAME;
                state_ = PluginState::TRYING;
                return false;
            }
            startState_ = StartState::WAIT_LT;
            state_ = PluginState::IDLE;
            return false;
        }
        case StartState::IN_TAG_NAME: {
            if (isWhitespace(c)) {
                startState_ = StartState::IN_ATTRS;
                state_ = PluginState::TRYING;
                return false;
            }
            if (c == u'/') {
                startLastNonWhitespace_ = c;
                startState_ = StartState::IN_ATTRS;
                state_ = PluginState::TRYING;
                return false;
            }
            if (c == u'>') {
                startState_ = StartState::WAIT_LT;
                state_ = PluginState::TRYING;
                return true;
            }
            if (!isTagNameContinuationChar(c)) {
                startState_ = StartState::WAIT_LT;
                state_ = PluginState::IDLE;
                tagName_.clear();
                return false;
            }
            tagName_.push_back(c);
            state_ = PluginState::TRYING;
            return false;
        }
        case StartState::IN_ATTRS: {
            if (startQuote_ != 0) {
                if (!isWhitespace(c)) startLastNonWhitespace_ = c;
                if (c == startQuote_) startQuote_ = 0;
                state_ = PluginState::TRYING;
                return false;
            }
            if (c == u'\'' || c == u'"') {
                startLastNonWhitespace_ = c;
                startQuote_ = c;
                state_ = PluginState::TRYING;
                return false;
            }
            if (c == u'>') {
                startState_ = StartState::WAIT_LT;
                state_ = PluginState::TRYING;
                return true;
            }
            if (!isWhitespace(c)) startLastNonWhitespace_ = c;
            state_ = PluginState::TRYING;
            return false;
        }
    }
    return false;
}

void StreamXmlPlugin::buildEndPattern() {
    if (isDisplayTagName(tagName_)) {
        displayEndMode_ = true;
        resetDisplayEndCandidate();
        displayFamilyStack_.clear();
        displayFamilyStack_.push_back(displayTagFamily(tagName_));
        haveEndPattern_ = false;
        return;
    }
    endPattern_.clear();
    endPattern_.reserve(tagName_.size() + 3);
    endPattern_.push_back(u'<');
    endPattern_.push_back(u'/');
    endPattern_.append(tagName_);
    endPattern_.push_back(u'>');
    endMatcher_.setPattern(endPattern_);
    haveEndPattern_ = true;
}

bool StreamXmlPlugin::isValidDisplayEndName() const {
    return !displayTagFamily(displayEndName_).empty();
}

std::u16string StreamXmlPlugin::displayTagFamily(const std::u16string& tagName) {
    if (isThinkFamilyTagName(tagName)) return u"think";
    if (equalsAsciiIgnoreCase(tagName, u"search")) return u"search";
    return {};
}

void StreamXmlPlugin::resetDisplayEndCandidate() {
    displayEndState_ = DisplayEndState::WAIT_LT;
    displayEndName_.clear();
    displayCandidateClosing_ = false;
    displayCandidateQuote_ = 0;
    displayLastNonWhitespace_ = 0;
}

void StreamXmlPlugin::restartDisplayEndMatcher(char16_t c) {
    resetDisplayEndCandidate();
    if (c == u'<') displayEndState_ = DisplayEndState::AFTER_LT;
}

bool StreamXmlPlugin::completeDisplayTagCandidate() {
    const std::u16string family = displayTagFamily(displayEndName_);
    const bool validName = !family.empty();
    const bool validClosingSuffix = displayLastNonWhitespace_ == 0;
    const bool selfClosing =
            !displayCandidateClosing_ && displayLastNonWhitespace_ == u'/';
    if (validName && displayCandidateClosing_ && validClosingSuffix) {
        if (!displayFamilyStack_.empty() && displayFamilyStack_.back() == family) {
            displayFamilyStack_.pop_back();
            if (displayFamilyStack_.empty()) return true;
        }
    } else if (validName && !displayCandidateClosing_ && !selfClosing) {
        displayFamilyStack_.push_back(family);
    }
    resetDisplayEndCandidate();
    return false;
}

bool StreamXmlPlugin::processDisplayEndMatcher(char16_t c) {
    switch (displayEndState_) {
        case DisplayEndState::WAIT_LT:
            if (c == u'<') displayEndState_ = DisplayEndState::AFTER_LT;
            return false;
        case DisplayEndState::AFTER_LT:
            if (c == u'/') {
                displayCandidateClosing_ = true;
                displayEndName_.clear();
                displayEndState_ = DisplayEndState::IN_NAME;
            } else if (isAsciiLetter(c)) {
                displayCandidateClosing_ = false;
                displayEndName_.assign(1, asciiLower(c));
                displayEndState_ = DisplayEndState::IN_NAME;
            } else {
                restartDisplayEndMatcher(c);
            }
            return false;
        case DisplayEndState::IN_NAME:
            if (isTagNameContinuationChar(c)) {
                displayEndName_.push_back(asciiLower(c));
                return false;
            }
            if (!isValidDisplayEndName()) {
                restartDisplayEndMatcher(c);
                return false;
            }
            if (c == u'>') return completeDisplayTagCandidate();
            if (isWhitespace(c)) {
                displayEndState_ = DisplayEndState::IN_SUFFIX;
                return false;
            }
            if (!displayCandidateClosing_ && c == u'/') {
                displayLastNonWhitespace_ = u'/';
                displayEndState_ = DisplayEndState::IN_SUFFIX;
                return false;
            }
            restartDisplayEndMatcher(c);
            return false;
        case DisplayEndState::IN_SUFFIX:
            if (displayCandidateQuote_ != 0) {
                if (!isWhitespace(c)) displayLastNonWhitespace_ = c;
                if (c == displayCandidateQuote_) displayCandidateQuote_ = 0;
                return false;
            }
            if (!displayCandidateClosing_ && (c == u'\'' || c == u'"')) {
                displayLastNonWhitespace_ = c;
                displayCandidateQuote_ = c;
                return false;
            }
            if (c == u'<') {
                restartDisplayEndMatcher(c);
                return false;
            }
            if (c == u'>') return completeDisplayTagCandidate();
            if (!isWhitespace(c)) displayLastNonWhitespace_ = c;
            return false;
    }
    return false;
}

bool StreamXmlPlugin::processChar(char16_t c, bool atStartOfLine) {
    auto finish = [&](bool result) {
        lastChar_ = c;
        return result;
    };

    if (state_ == PluginState::PROCESSING) {
        if (displayEndMode_ && processDisplayEndMatcher(c)) {
            allowStartAfterEndTag_ = true;
            allowStartAfterPunctuation_ = false;
            reset();
            return finish(includeTagsInOutput_);
        }
        if (haveEndPattern_) {
            if (endMatcher_.process(c)) {
                allowStartAfterEndTag_ = true;
                allowStartAfterPunctuation_ = false;
                reset();
                return finish(includeTagsInOutput_);
            }
        }
        return finish(includeTagsInOutput_);
    }

    if (state_ == PluginState::IDLE && !atStartOfLine) {
        const bool allowStart = allowStartAfterEndTag_ || allowStartAfterPunctuation_;
        if (!allowStart) {
            return finish(handleDefaultCharacter(c));
        }
        if (c == u' ' || c == u'\t' || isEmojiContinuationChar(c)) {
            return finish(handleDefaultCharacter(c));
        }
    }

    const PluginState previousState = state_;
    const bool startMatched = processStartMatcher(c);

    if (startMatched) {
        if (startLastNonWhitespace_ == u'/') {
            // Treat self-closing tags like <br/> as plain text to avoid entering XML mode.
            const bool exposeAdjacentTag = isDisplayTagName(tagName_);
            reset();
            // Only app-owned display tags may expose an immediately adjacent tool. Arbitrary
            // self-closing HTML/XML remains plain text and does not broaden executable contexts.
            allowStartAfterEndTag_ = exposeAdjacentTag;
            return finish(true);
        }
        state_ = PluginState::PROCESSING;
        allowStartAfterEndTag_ = false;
        allowStartAfterPunctuation_ = false;
        buildEndPattern();
        startState_ = StartState::WAIT_LT;
        startQuote_ = 0;
        startLastNonWhitespace_ = 0;
        return finish(includeTagsInOutput_);
    }

    if (state_ == PluginState::TRYING) {
        allowStartAfterPunctuation_ = false;
        return finish(includeTagsInOutput_);
    }

    if (previousState == PluginState::TRYING) {
        reset();
    }
    allowStartAfterEndTag_ = false;
    allowStartAfterPunctuation_ = false;
    return finish(handleDefaultCharacter(c));
}

} // namespace streamnative
