package com.ai.assistance.operit.core.tools.javascript

import com.ai.assistance.operit.util.AppLogger
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

internal typealias JsInterfaceCallbackInvoker = (jsObjectId: String, methodName: String, argsJson: String) -> String
internal typealias JsInterfaceReleaseInvoker = (jsObjectId: String) -> Unit

internal object JsJavaBridgeDelegates {
    private const val TAG = "JsJavaBridge"
    private const val HANDLE_KEY = "__javaHandle"
    private const val CLASS_KEY = "__javaClass"
    private const val JS_INTERFACE_KEY = "__javaJsInterface"
    private const val JS_OBJECT_ID_KEY = "__javaJsObjectId"
    private const val JS_INTERFACES_KEY = "__javaInterfaces"
    private const val BRIDGE_NUMBER_PREFIX = "\u0000operit-java-number:"
    private const val BRIDGE_STRING_ESCAPE_PREFIX = "${BRIDGE_NUMBER_PREFIX}STRING:"
    private const val MAX_BRIDGE_SERIALIZATION_DEPTH = 64
    private const val MAX_BRIDGE_SERIALIZATION_ELEMENTS = 65_536
    private const val MAX_BRIDGE_SERIALIZATION_SCALAR_CHARS = 1_048_576
    private const val MAX_BRIDGE_INPUT_JSON_CHARS = 1_048_576
    private const val MAX_BRIDGE_MUTABLE_CONTAINER_SIZE = 65_536
    private const val MAX_BRIDGE_STRING_BUILDER_CHARS = 65_536
    private const val MAX_BRIDGE_LIVE_OBJECT_HANDLES = 1_024

    private val primitiveWrapperMap: Map<Class<*>, Class<*>> =
        mapOf(
            java.lang.Boolean.TYPE to java.lang.Boolean::class.java,
            java.lang.Byte.TYPE to java.lang.Byte::class.java,
            java.lang.Short.TYPE to java.lang.Short::class.java,
            java.lang.Integer.TYPE to java.lang.Integer::class.java,
            java.lang.Long.TYPE to java.lang.Long::class.java,
            java.lang.Float.TYPE to java.lang.Float::class.java,
            java.lang.Double.TYPE to java.lang.Double::class.java,
            java.lang.Character.TYPE to java.lang.Character::class.java
        )

    private data class ConvertedArg(
        val value: Any?,
        val score: Int
    )

    private data class MethodMatch(
        val method: Method,
        val args: Array<Any?>,
        val score: Int
    )

    private data class ConstructorMatch(
        val constructor: Constructor<*>,
        val args: Array<Any?>,
        val score: Int
    )

    private data class ConstructedJavaObject(
        val value: Any
    )

    private data class JsInterfaceBinding(
        val jsObjectId: String,
        val interfaceNames: List<String>
    )

    private data class BridgeResponse(
        val success: Boolean,
        val dataRaw: Any?,
        val error: String?
    )

    private class BridgeHandleRegistrationTransaction {
        val handlesByIdentity = IdentityHashMap<Any, String>()
        val insertedHandles = mutableListOf<Pair<String, Any>>()
    }

    private class JsInterfaceProxyReference(
        referent: Any,
        val callbackInvoker: JsInterfaceCallbackInvoker,
        val jsObjectIds: Set<String>
    ) : PhantomReference<Any>(referent, jsInterfaceProxyReferenceQueue)

    private val jsInterfaceProxyReferenceQueue = ReferenceQueue<Any>()
    private val jsInterfaceProxyReferences =
        Collections.newSetFromMap(ConcurrentHashMap<JsInterfaceProxyReference, Boolean>())
    private val jsInterfaceLifecycleLock = Any()
    private val jsInterfaceReleaseInvokers =
        IdentityHashMap<JsInterfaceCallbackInvoker, JsInterfaceReleaseInvoker>()
    private val jsInterfaceReferenceCounts =
        IdentityHashMap<JsInterfaceCallbackInvoker, MutableMap<String, Int>>()
    private val jsInterfaceLifecycleWorkerStarted = AtomicBoolean(false)
    private val primitiveWrapperClassNames =
        setOf(
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Character",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short"
        )
    private val allowedClassNames =
        setOf(
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Character",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Number",
            "java.lang.Short",
            "java.lang.String",
            "java.lang.StringBuilder",
            "java.util.ArrayList",
            "java.util.Collections",
            "java.util.HashMap",
            "java.util.HashSet",
            "java.util.LinkedHashMap",
            "java.util.LinkedHashSet",
            "java.util.UUID"
        )
    private val allowedInterfaceProxyClassNames = emptySet<String>()
    private fun methodNames(names: String): Set<String> =
        names.split(' ').filter(String::isNotBlank).toSet()

    private fun methodSignature(
        className: String,
        methodName: String,
        parameterTypeNames: List<String>
    ): String = "$className#$methodName(${parameterTypeNames.joinToString(",")})"

    private fun methodSignatures(signatures: String): Set<String> =
        signatures
            .lineSequence()
            .flatMap { it.trim().splitToSequence(' ') }
            .filter(String::isNotBlank)
            .toSet()

    private val safeStringCharSequenceMethodSignatures =
        setOf(
            methodSignature(
                "java.lang.String",
                "contains",
                listOf("java.lang.CharSequence")
            ),
            methodSignature(
                "java.lang.String",
                "contentEquals",
                listOf("java.lang.CharSequence")
            )
        )
    private val allowedMethodNamesByClass =
        mapOf(
            "java.lang.Boolean" to
                methodNames(
                    "booleanValue compare compareTo equals hashCode logicalAnd logicalOr " +
                        "logicalXor parseBoolean toString valueOf"
                ),
            "java.lang.Byte" to
                methodNames(
                    "byteValue compare compareTo decode doubleValue equals floatValue hashCode " +
                        "intValue longValue parseByte shortValue toString toUnsignedInt " +
                        "toUnsignedLong valueOf"
                ),
            "java.lang.Character" to
                methodNames(
                    "charCount charValue codePointAt codePointBefore codePointCount compare " +
                        "compareTo digit equals forDigit getDirectionality getName getNumericValue " +
                        "getType hashCode highSurrogate isAlphabetic isBmpCodePoint isDefined " +
                        "isDigit isHighSurrogate isIdentifierIgnorable isIdeographic isISOControl " +
                        "isJavaIdentifierPart isJavaIdentifierStart isJavaLetter " +
                        "isJavaLetterOrDigit isLetter isLetterOrDigit isLowerCase isLowSurrogate " +
                        "isMirrored isSpace isSpaceChar isSupplementaryCodePoint isSurrogate " +
                        "isSurrogatePair isTitleCase isUnicodeIdentifierPart " +
                        "isUnicodeIdentifierStart isUpperCase isValidCodePoint isWhitespace " +
                        "lowSurrogate offsetByCodePoints reverseBytes toChars toCodePoint " +
                        "toLowerCase toString toTitleCase toUpperCase valueOf"
                ),
            "java.lang.Double" to
                methodNames(
                    "byteValue compare compareTo doubleToLongBits doubleToRawLongBits doubleValue " +
                        "equals floatValue hashCode intValue isFinite isInfinite isNaN longBitsToDouble " +
                        "longValue max min parseDouble shortValue sum toHexString toString valueOf"
                ),
            "java.lang.Float" to
                methodNames(
                    "byteValue compare compareTo doubleValue equals floatToIntBits floatToRawIntBits " +
                        "floatValue hashCode intBitsToFloat intValue isFinite isInfinite isNaN " +
                        "longValue max min parseFloat shortValue sum toHexString toString valueOf"
                ),
            "java.lang.Integer" to
                methodNames(
                    "bitCount byteValue compare compareTo compareUnsigned decode divideUnsigned " +
                        "doubleValue equals floatValue hashCode highestOneBit intValue longValue " +
                        "lowestOneBit max min numberOfLeadingZeros numberOfTrailingZeros parseInt " +
                        "parseUnsignedInt remainderUnsigned reverse reverseBytes rotateLeft rotateRight " +
                        "shortValue signum sum toBinaryString toHexString toOctalString toString " +
                        "toUnsignedLong toUnsignedString valueOf"
                ),
            "java.lang.Long" to
                methodNames(
                    "bitCount byteValue compare compareTo compareUnsigned decode divideUnsigned " +
                        "doubleValue equals floatValue hashCode highestOneBit intValue longValue " +
                        "lowestOneBit max min numberOfLeadingZeros numberOfTrailingZeros parseLong " +
                        "parseUnsignedLong remainderUnsigned reverse reverseBytes rotateLeft rotateRight " +
                        "shortValue signum sum toBinaryString toHexString toOctalString toString " +
                        "toUnsignedString valueOf"
                ),
            "java.lang.Short" to
                methodNames(
                    "byteValue compare compareTo decode doubleValue equals floatValue hashCode " +
                        "intValue longValue parseShort reverseBytes shortValue toString toUnsignedInt " +
                        "toUnsignedLong valueOf"
                ),
            "java.util.UUID" to
                methodNames(
                    "clockSequence compareTo equals fromString getLeastSignificantBits " +
                        "getMostSignificantBits hashCode nameUUIDFromBytes node randomUUID timestamp " +
                        "toString variant version"
                ),
            "java.lang.String" to
                setOf(
                    "charAt",
                    "codePointAt",
                    "codePointBefore",
                    "codePointCount",
                    "compareTo",
                    "compareToIgnoreCase",
                    "concat",
                    "contains",
                    "contentEquals",
                    "copyValueOf",
                    "endsWith",
                    "equals",
                    "equalsIgnoreCase",
                    "getBytes",
                    "getChars",
                    "hashCode",
                    "indexOf",
                    "isEmpty",
                    "lastIndexOf",
                    "length",
                    "regionMatches",
                    "startsWith",
                    "subSequence",
                    "substring",
                    "toCharArray",
                    "toLowerCase",
                    "toString",
                    "toUpperCase",
                    "trim",
                    "valueOf"
                ),
            "java.lang.StringBuilder" to
                setOf(
                    "append",
                    "appendCodePoint",
                    "capacity",
                    "charAt",
                    "codePointAt",
                    "codePointBefore",
                    "codePointCount",
                    "delete",
                    "deleteCharAt",
                    "equals",
                    "getChars",
                    "hashCode",
                    "indexOf",
                    "insert",
                    "lastIndexOf",
                    "length",
                    "offsetByCodePoints",
                    "replace",
                    "reverse",
                    "setCharAt",
                    "subSequence",
                    "substring",
                    "toString",
                    "trimToSize"
                ),
            "java.util.ArrayList" to
                setOf(
                    "add",
                    "addAll",
                    "clear",
                    "clone",
                    "contains",
                    "equals",
                    "get",
                    "hashCode",
                    "indexOf",
                    "isEmpty",
                    "lastIndexOf",
                    "remove",
                    "set",
                    "size",
                    "subList",
                    "toArray",
                    "toString",
                    "trimToSize"
                ),
            "java.util.HashMap" to
                setOf(
                    "clear",
                    "clone",
                    "containsKey",
                    "containsValue",
                    "equals",
                    "get",
                    "getOrDefault",
                    "hashCode",
                    "isEmpty",
                    "put",
                    "putAll",
                    "putIfAbsent",
                    "remove",
                    "replace",
                    "size",
                    "toString"
                ),
            "java.util.LinkedHashMap" to
                setOf(
                    "clear",
                    "clone",
                    "containsKey",
                    "containsValue",
                    "equals",
                    "get",
                    "getOrDefault",
                    "hashCode",
                    "isEmpty",
                    "put",
                    "putAll",
                    "putIfAbsent",
                    "remove",
                    "replace",
                    "size",
                    "toString"
                ),
            "java.util.HashSet" to
                setOf(
                    "add",
                    "addAll",
                    "clear",
                    "clone",
                    "contains",
                    "containsAll",
                    "equals",
                    "hashCode",
                    "isEmpty",
                    "remove",
                    "size",
                    "toArray",
                    "toString"
                ),
            "java.util.LinkedHashSet" to
                setOf(
                    "add",
                    "addAll",
                    "clear",
                    "clone",
                    "contains",
                    "containsAll",
                    "equals",
                    "hashCode",
                    "isEmpty",
                    "remove",
                    "size",
                    "toArray",
                    "toString"
                ),
            "java.util.Collections" to
                setOf(
                    "addAll",
                    "binarySearch",
                    "copy",
                    "emptyList",
                    "emptyMap",
                    "emptySet",
                    "fill",
                    "frequency",
                    "max",
                    "min",
                    "nCopies",
                    "replaceAll",
                    "reverse",
                    "rotate",
                    "shuffle",
                    "singleton",
                    "singletonList",
                    "singletonMap",
                    "sort",
                    "swap"
                )
        )
    // Exact public signatures available on Android API 26. Method names remain a readability
    // profile above, but this frozen set is the authority so platform-added overloads never gain
    // bridge access implicitly. StringBuilder entries include public methods inherited from the
    // hidden AbstractStringBuilder superclass, which api-versions.xml does not list as a public API.
    private val allowedMethodSignaturesByClass =
        mapOf(
            "java.lang.Boolean" to
                methodSignatures(
                    """
                    booleanValue() compare(boolean,boolean) compareTo(java.lang.Boolean) compareTo(java.lang.Object)
                    equals(java.lang.Object) hashCode() hashCode(boolean) logicalAnd(boolean,boolean)
                    logicalOr(boolean,boolean) logicalXor(boolean,boolean) parseBoolean(java.lang.String) toString()
                    toString(boolean) valueOf(boolean) valueOf(java.lang.String)
                    """
                ),
            "java.lang.Byte" to
                methodSignatures(
                    """
                    byteValue() compare(byte,byte) compareTo(java.lang.Byte) compareTo(java.lang.Object)
                    decode(java.lang.String) doubleValue() equals(java.lang.Object) floatValue()
                    hashCode() hashCode(byte) intValue() longValue()
                    parseByte(java.lang.String,int) parseByte(java.lang.String) shortValue() toString()
                    toString(byte) toUnsignedInt(byte) toUnsignedLong(byte) valueOf(byte)
                    valueOf(java.lang.String,int) valueOf(java.lang.String)
                    """
                ),
            "java.lang.Character" to
                methodSignatures(
                    """
                    charCount(int) charValue() codePointAt([C,int,int) codePointAt([C,int)
                    codePointAt(java.lang.CharSequence,int) codePointBefore([C,int,int) codePointBefore([C,int) codePointBefore(java.lang.CharSequence,int)
                    codePointCount([C,int,int) codePointCount(java.lang.CharSequence,int,int) compare(char,char) compareTo(java.lang.Character)
                    compareTo(java.lang.Object) digit(char,int) digit(int,int) equals(java.lang.Object)
                    forDigit(int,int) getDirectionality(char) getDirectionality(int) getName(int)
                    getNumericValue(char) getNumericValue(int) getType(char) getType(int)
                    hashCode() hashCode(char) highSurrogate(int) isAlphabetic(int)
                    isBmpCodePoint(int) isDefined(char) isDefined(int) isDigit(char)
                    isDigit(int) isHighSurrogate(char) isIdentifierIgnorable(char) isIdentifierIgnorable(int)
                    isIdeographic(int) isISOControl(char) isISOControl(int) isJavaIdentifierPart(char)
                    isJavaIdentifierPart(int) isJavaIdentifierStart(char) isJavaIdentifierStart(int) isJavaLetter(char)
                    isJavaLetterOrDigit(char) isLetter(char) isLetter(int) isLetterOrDigit(char)
                    isLetterOrDigit(int) isLowerCase(char) isLowerCase(int) isLowSurrogate(char)
                    isMirrored(char) isMirrored(int) isSpace(char) isSpaceChar(char)
                    isSpaceChar(int) isSupplementaryCodePoint(int) isSurrogate(char) isSurrogatePair(char,char)
                    isTitleCase(char) isTitleCase(int) isUnicodeIdentifierPart(char) isUnicodeIdentifierPart(int)
                    isUnicodeIdentifierStart(char) isUnicodeIdentifierStart(int) isUpperCase(char) isUpperCase(int)
                    isValidCodePoint(int) isWhitespace(char) isWhitespace(int) lowSurrogate(int)
                    offsetByCodePoints([C,int,int,int,int) offsetByCodePoints(java.lang.CharSequence,int,int) reverseBytes(char) toChars(int,[C,int)
                    toChars(int) toCodePoint(char,char) toLowerCase(char) toLowerCase(int)
                    toString() toString(char) toTitleCase(char) toTitleCase(int)
                    toUpperCase(char) toUpperCase(int) valueOf(char)
                    """
                ),
            "java.lang.Double" to
                methodSignatures(
                    """
                    byteValue() compare(double,double) compareTo(java.lang.Double) compareTo(java.lang.Object)
                    doubleToLongBits(double) doubleToRawLongBits(double) doubleValue() equals(java.lang.Object)
                    floatValue() hashCode() hashCode(double) intValue()
                    isFinite(double) isInfinite() isInfinite(double) isNaN()
                    isNaN(double) longBitsToDouble(long) longValue() max(double,double)
                    min(double,double) parseDouble(java.lang.String) shortValue() sum(double,double)
                    toHexString(double) toString() toString(double) valueOf(double)
                    valueOf(java.lang.String)
                    """
                ),
            "java.lang.Float" to
                methodSignatures(
                    """
                    byteValue() compare(float,float) compareTo(java.lang.Float) compareTo(java.lang.Object)
                    doubleValue() equals(java.lang.Object) floatToIntBits(float) floatToRawIntBits(float)
                    floatValue() hashCode() hashCode(float) intBitsToFloat(int)
                    intValue() isFinite(float) isInfinite() isInfinite(float)
                    isNaN() isNaN(float) longValue() max(float,float)
                    min(float,float) parseFloat(java.lang.String) shortValue() sum(float,float)
                    toHexString(float) toString() toString(float) valueOf(float)
                    valueOf(java.lang.String)
                    """
                ),
            "java.lang.Integer" to
                methodSignatures(
                    """
                    bitCount(int) byteValue() compare(int,int) compareTo(java.lang.Integer)
                    compareTo(java.lang.Object) compareUnsigned(int,int) decode(java.lang.String) divideUnsigned(int,int)
                    doubleValue() equals(java.lang.Object) floatValue() hashCode()
                    hashCode(int) highestOneBit(int) intValue() longValue()
                    lowestOneBit(int) max(int,int) min(int,int) numberOfLeadingZeros(int)
                    numberOfTrailingZeros(int) parseInt(java.lang.String,int) parseInt(java.lang.String) parseUnsignedInt(java.lang.String,int)
                    parseUnsignedInt(java.lang.String) remainderUnsigned(int,int) reverse(int) reverseBytes(int)
                    rotateLeft(int,int) rotateRight(int,int) shortValue() signum(int)
                    sum(int,int) toBinaryString(int) toHexString(int) toOctalString(int)
                    toString() toString(int,int) toString(int) toUnsignedLong(int)
                    toUnsignedString(int,int) toUnsignedString(int) valueOf(int) valueOf(java.lang.String,int)
                    valueOf(java.lang.String)
                    """
                ),
            "java.lang.Long" to
                methodSignatures(
                    """
                    bitCount(long) byteValue() compare(long,long) compareTo(java.lang.Long)
                    compareTo(java.lang.Object) compareUnsigned(long,long) decode(java.lang.String) divideUnsigned(long,long)
                    doubleValue() equals(java.lang.Object) floatValue() hashCode()
                    hashCode(long) highestOneBit(long) intValue() longValue()
                    lowestOneBit(long) max(long,long) min(long,long) numberOfLeadingZeros(long)
                    numberOfTrailingZeros(long) parseLong(java.lang.String,int) parseLong(java.lang.String) parseUnsignedLong(java.lang.String,int)
                    parseUnsignedLong(java.lang.String) remainderUnsigned(long,long) reverse(long) reverseBytes(long)
                    rotateLeft(long,int) rotateRight(long,int) shortValue() signum(long)
                    sum(long,long) toBinaryString(long) toHexString(long) toOctalString(long)
                    toString() toString(long,int) toString(long) toUnsignedString(long,int)
                    toUnsignedString(long) valueOf(java.lang.String,int) valueOf(java.lang.String) valueOf(long)
                    """
                ),
            "java.lang.Short" to
                methodSignatures(
                    """
                    byteValue() compare(short,short) compareTo(java.lang.Object) compareTo(java.lang.Short)
                    decode(java.lang.String) doubleValue() equals(java.lang.Object) floatValue()
                    hashCode() hashCode(short) intValue() longValue()
                    parseShort(java.lang.String,int) parseShort(java.lang.String) reverseBytes(short) shortValue()
                    toString() toString(short) toUnsignedInt(short) toUnsignedLong(short)
                    valueOf(java.lang.String,int) valueOf(java.lang.String) valueOf(short)
                    """
                ),
            "java.lang.String" to
                methodSignatures(
                    """
                    charAt(int) codePointAt(int) codePointBefore(int) codePointCount(int,int)
                    compareTo(java.lang.Object) compareTo(java.lang.String) compareToIgnoreCase(java.lang.String) concat(java.lang.String)
                    contains(java.lang.CharSequence) contentEquals(java.lang.CharSequence) contentEquals(java.lang.StringBuffer) copyValueOf([C,int,int)
                    copyValueOf([C) endsWith(java.lang.String) equals(java.lang.Object) equalsIgnoreCase(java.lang.String)
                    getBytes() getBytes(int,int,[B,int) getBytes(java.lang.String) getBytes(java.nio.charset.Charset)
                    getChars(int,int,[C,int) hashCode() indexOf(int,int) indexOf(int)
                    indexOf(java.lang.String,int) indexOf(java.lang.String) isEmpty() lastIndexOf(int,int)
                    lastIndexOf(int) lastIndexOf(java.lang.String,int) lastIndexOf(java.lang.String) length()
                    regionMatches(boolean,int,java.lang.String,int,int) regionMatches(int,java.lang.String,int,int) startsWith(java.lang.String,int) startsWith(java.lang.String)
                    subSequence(int,int) substring(int,int) substring(int) toCharArray()
                    toLowerCase() toLowerCase(java.util.Locale) toString() toUpperCase()
                    toUpperCase(java.util.Locale) trim() valueOf([C,int,int) valueOf([C)
                    valueOf(boolean) valueOf(char) valueOf(double) valueOf(float)
                    valueOf(int) valueOf(java.lang.Object) valueOf(long)
                    """
                ),
            "java.lang.StringBuilder" to
                methodSignatures(
                    """
                    append([C,int,int) append([C) append(boolean) append(char)
                    append(double) append(float) append(int) append(java.lang.CharSequence,int,int)
                    append(java.lang.CharSequence) append(java.lang.Object) append(java.lang.String) append(java.lang.StringBuffer)
                    append(long) appendCodePoint(int) capacity() charAt(int)
                    codePointAt(int) codePointBefore(int) codePointCount(int,int) delete(int,int)
                    deleteCharAt(int) equals(java.lang.Object) getChars(int,int,[C,int) hashCode()
                    indexOf(java.lang.String,int) indexOf(java.lang.String) insert(int,[C,int,int) insert(int,[C)
                    insert(int,boolean) insert(int,char) insert(int,double) insert(int,float)
                    insert(int,int) insert(int,java.lang.CharSequence,int,int) insert(int,java.lang.CharSequence) insert(int,java.lang.Object)
                    insert(int,java.lang.String) insert(int,long) lastIndexOf(java.lang.String,int) lastIndexOf(java.lang.String)
                    length() offsetByCodePoints(int,int) replace(int,int,java.lang.String) reverse()
                    setCharAt(int,char) subSequence(int,int) substring(int,int) substring(int)
                    toString() trimToSize()
                    """
                ),
            "java.util.ArrayList" to
                methodSignatures(
                    """
                    add(int,java.lang.Object) add(java.lang.Object) addAll(int,java.util.Collection) addAll(java.util.Collection)
                    clear() clone() contains(java.lang.Object) equals(java.lang.Object)
                    get(int) hashCode() indexOf(java.lang.Object) isEmpty()
                    lastIndexOf(java.lang.Object) remove(int) remove(java.lang.Object) set(int,java.lang.Object)
                    size() subList(int,int) toArray() toArray([Ljava.lang.Object;)
                    toString() trimToSize()
                    """
                ),
            "java.util.Collections" to
                methodSignatures(
                    """
                    addAll(java.util.Collection,[Ljava.lang.Object;) binarySearch(java.util.List,java.lang.Object,java.util.Comparator) binarySearch(java.util.List,java.lang.Object) copy(java.util.List,java.util.List)
                    emptyList() emptyMap() emptySet() fill(java.util.List,java.lang.Object)
                    frequency(java.util.Collection,java.lang.Object) max(java.util.Collection,java.util.Comparator) max(java.util.Collection) min(java.util.Collection,java.util.Comparator)
                    min(java.util.Collection) nCopies(int,java.lang.Object) replaceAll(java.util.List,java.lang.Object,java.lang.Object) reverse(java.util.List)
                    rotate(java.util.List,int) shuffle(java.util.List,java.util.Random) shuffle(java.util.List) singleton(java.lang.Object)
                    singletonList(java.lang.Object) singletonMap(java.lang.Object,java.lang.Object) sort(java.util.List,java.util.Comparator) sort(java.util.List)
                    swap(java.util.List,int,int)
                    """
                ),
            "java.util.HashMap" to
                methodSignatures(
                    """
                    clear() clone() containsKey(java.lang.Object) containsValue(java.lang.Object)
                    equals(java.lang.Object) get(java.lang.Object) getOrDefault(java.lang.Object,java.lang.Object) hashCode()
                    isEmpty() put(java.lang.Object,java.lang.Object) putAll(java.util.Map) putIfAbsent(java.lang.Object,java.lang.Object)
                    remove(java.lang.Object,java.lang.Object) remove(java.lang.Object) replace(java.lang.Object,java.lang.Object,java.lang.Object) replace(java.lang.Object,java.lang.Object)
                    size() toString()
                    """
                ),
            "java.util.HashSet" to
                methodSignatures(
                    """
                    add(java.lang.Object) addAll(java.util.Collection) clear() clone()
                    contains(java.lang.Object) containsAll(java.util.Collection) equals(java.lang.Object) hashCode()
                    isEmpty() remove(java.lang.Object) size() toArray()
                    toArray([Ljava.lang.Object;) toString()
                    """
                ),
            "java.util.LinkedHashMap" to
                methodSignatures(
                    """
                    clear() clone() containsKey(java.lang.Object) containsValue(java.lang.Object)
                    equals(java.lang.Object) get(java.lang.Object) getOrDefault(java.lang.Object,java.lang.Object) hashCode()
                    isEmpty() put(java.lang.Object,java.lang.Object) putAll(java.util.Map) putIfAbsent(java.lang.Object,java.lang.Object)
                    remove(java.lang.Object,java.lang.Object) remove(java.lang.Object) replace(java.lang.Object,java.lang.Object,java.lang.Object) replace(java.lang.Object,java.lang.Object)
                    size() toString()
                    """
                ),
            "java.util.LinkedHashSet" to
                methodSignatures(
                    """
                    add(java.lang.Object) addAll(java.util.Collection) clear() clone()
                    contains(java.lang.Object) containsAll(java.util.Collection) equals(java.lang.Object) hashCode()
                    isEmpty() remove(java.lang.Object) size() toArray()
                    toArray([Ljava.lang.Object;) toString()
                    """
                ),
            "java.util.UUID" to
                methodSignatures(
                    """
                    clockSequence() compareTo(java.lang.Object) compareTo(java.util.UUID) equals(java.lang.Object)
                    fromString(java.lang.String) getLeastSignificantBits() getMostSignificantBits() hashCode()
                    nameUUIDFromBytes([B) node() randomUUID() timestamp()
                    toString() variant() version()
                    """
                )
        ).also { signaturesByClass ->
            check(signaturesByClass.keys == allowedMethodNamesByClass.keys) {
                "Java bridge method-name and signature profiles must cover the same classes"
            }
            signaturesByClass.forEach { (className, signatures) ->
                val signatureMethodNames = signatures.mapTo(mutableSetOf()) { it.substringBefore('(') }
                check(signatureMethodNames == allowedMethodNamesByClass.getValue(className)) {
                    "Java bridge signatures for $className must match its method-name profile"
                }
            }
        }

    private val allowedStaticFieldNamesByClass =
        mapOf(
            "java.lang.Boolean" to setOf("FALSE", "TRUE", "TYPE"),
            "java.lang.Byte" to setOf("BYTES", "MAX_VALUE", "MIN_VALUE", "SIZE", "TYPE"),
            "java.lang.Character" to
                setOf(
                    "BYTES",
                    "MAX_CODE_POINT",
                    "MAX_HIGH_SURROGATE",
                    "MAX_LOW_SURROGATE",
                    "MAX_RADIX",
                    "MAX_SURROGATE",
                    "MAX_VALUE",
                    "MIN_CODE_POINT",
                    "MIN_HIGH_SURROGATE",
                    "MIN_LOW_SURROGATE",
                    "MIN_RADIX",
                    "MIN_SUPPLEMENTARY_CODE_POINT",
                    "MIN_SURROGATE",
                    "MIN_VALUE",
                    "SIZE",
                    "TYPE"
                ),
            "java.lang.Double" to
                setOf(
                    "BYTES",
                    "MAX_EXPONENT",
                    "MAX_VALUE",
                    "MIN_EXPONENT",
                    "MIN_NORMAL",
                    "MIN_VALUE",
                    "NaN",
                    "NEGATIVE_INFINITY",
                    "POSITIVE_INFINITY",
                    "SIZE",
                    "TYPE"
                ),
            "java.lang.Float" to
                setOf(
                    "BYTES",
                    "MAX_EXPONENT",
                    "MAX_VALUE",
                    "MIN_EXPONENT",
                    "MIN_NORMAL",
                    "MIN_VALUE",
                    "NaN",
                    "NEGATIVE_INFINITY",
                    "POSITIVE_INFINITY",
                    "SIZE",
                    "TYPE"
                ),
            "java.lang.Integer" to
                setOf("BYTES", "MAX_VALUE", "MIN_VALUE", "SIZE", "TYPE"),
            "java.lang.Long" to setOf("BYTES", "MAX_VALUE", "MIN_VALUE", "SIZE", "TYPE"),
            "java.lang.Short" to setOf("BYTES", "MAX_VALUE", "MIN_VALUE", "SIZE", "TYPE")
        )

    internal fun parseJsonObject(raw: Any?): JSONObject? {
        return when (raw) {
            is JSONObject -> raw
            is Map<*, *> -> toPlainJsonValue(raw) as? JSONObject
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) {
                    null
                } else {
                    runCatching {
                        when (val token = JSONTokener(trimmed).nextValue()) {
                            is JSONObject -> token
                            is String -> JSONObject(token)
                            else -> null
                        }
                    }.getOrNull()
                }
            }

            else -> raw?.toString()?.let(::parseJsonObject)
        }
    }

    internal fun parsePlainJsonObjectToMap(raw: String): Map<String, Any?> {
        val parsed = parseJsonObject(raw) ?: return emptyMap()
        return decodePlainJsonValue(parsed) as? Map<String, Any?> ?: emptyMap()
    }

    internal fun parsePlainJsonValueOrRawString(raw: String?): Any? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) {
            return null
        }
        return runCatching {
            decodePlainJsonValue(JSONTokener(trimmed).nextValue())
        }.getOrElse {
            raw
        }
    }

    internal fun parsePlainJsonArray(raw: String): List<Any?>? {
        val normalized = raw.trim().ifBlank { "[]" }
        val token = runCatching { JSONTokener(normalized).nextValue() }.getOrNull() ?: return null
        return when (token) {
            is JSONArray -> decodePlainJsonValue(token) as? List<Any?>
            is List<*> -> token.map { item -> decodePlainJsonValue(item) }
            else -> null
        }
    }

    internal fun decodePlainJsonValue(raw: Any?): Any? {
        return decodeJsonValue(
            raw = raw,
            objectRegistry = ConcurrentHashMap(),
            interpretBridgeMarkers = false
        )
    }

    internal fun toPlainJsonValue(value: Any?): Any {
        return toJsonCompatibleValue(
            value = value,
            objectRegistry = ConcurrentHashMap(),
            bridgeAware = false
        )
    }

    internal fun toPlainJsonText(value: Any?): String {
        return when (val jsonValue = toPlainJsonValue(value)) {
            JSONObject.NULL -> "null"
            is JSONObject -> jsonValue.toString()
            is JSONArray -> jsonValue.toString()
            is String -> JSONObject.quote(jsonValue)
            is Number, is Boolean -> jsonValue.toString()
            else -> throw IllegalArgumentException(
                "value is not JSON-serializable: ${jsonValue.javaClass.name}"
            )
        }
    }

    internal fun containsBridgeMarkers(raw: Any?): Boolean {
        return when (raw) {
            null, JSONObject.NULL -> false
            is JSONObject -> {
                if (
                    (raw.has(HANDLE_KEY) && raw.has(CLASS_KEY)) ||
                        (
                            (raw.optBoolean(JS_INTERFACE_KEY, false) || raw.has(JS_OBJECT_ID_KEY)) &&
                                raw.optString(JS_OBJECT_ID_KEY).trim().isNotEmpty()
                            )
                ) {
                    true
                } else {
                    raw.keys().asSequence().any { key ->
                        containsBridgeMarkers(raw.opt(key))
                    }
                }
            }

            is JSONArray -> {
                (0 until raw.length()).any { index ->
                    containsBridgeMarkers(raw.opt(index))
                }
            }

            is Map<*, *> -> {
                raw.values.any(::containsBridgeMarkers)
            }

            is Iterable<*> -> {
                raw.any(::containsBridgeMarkers)
            }

            else -> false
        }
    }

    fun registerJsInterfaceReleaseInvoker(
        callbackInvoker: JsInterfaceCallbackInvoker,
        releaseInvoker: JsInterfaceReleaseInvoker
    ) {
        ensureJsInterfaceLifecycleWorker()
        synchronized(jsInterfaceLifecycleLock) {
            jsInterfaceReleaseInvokers[callbackInvoker] = releaseInvoker
        }
    }

    fun unregisterJsInterfaceReleaseInvoker(callbackInvoker: JsInterfaceCallbackInvoker) {
        synchronized(jsInterfaceLifecycleLock) {
            jsInterfaceReleaseInvokers.remove(callbackInvoker)
            jsInterfaceReferenceCounts.remove(callbackInvoker)
        }
    }

    fun classExists(className: String, bridgeClassLoader: ClassLoader? = null): Boolean {
        return try {
            loadClass(className, bridgeClassLoader)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun newInstance(
        className: String,
        argsJson: String,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker? = null,
        bridgeClassLoader: ClassLoader? = null
    ): String {
        return runBridgeCall(objectRegistry) {
            val clazz = loadClass(className, bridgeClassLoader)
            val rawArgs = parseArgsJson(argsJson, objectRegistry)
            val constructorMatch =
                selectConstructor(
                    clazz = clazz,
                    rawArgs = rawArgs,
                    objectRegistry = objectRegistry,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                )
            validateJavaBridgeConstructorInvocation(
                constructorMatch.constructor,
                constructorMatch.args
            )
            ConstructedJavaObject(constructorMatch.constructor.newInstance(*constructorMatch.args))
        }
    }

    fun callStatic(
        className: String,
        methodName: String,
        argsJson: String,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker? = null,
        bridgeClassLoader: ClassLoader? = null
    ): String {
        return runBridgeCall(objectRegistry) {
            val clazz = loadClass(className, bridgeClassLoader)
            val normalizedMethodName = methodName.trim()
            require(normalizedMethodName.isNotEmpty()) { "method name is required" }

            val rawArgs =
                parseArgsJsonInternal(
                    argsJson = argsJson,
                    objectRegistry = objectRegistry,
                    validateDecodedGraph =
                        !(clazz == Collections::class.java && normalizedMethodName == "addAll")
                )
            val staticMethodMatch =
                try {
                    selectMethod(
                        clazz = clazz,
                        methodName = normalizedMethodName,
                        rawArgs = rawArgs,
                        staticOnly = true,
                        objectRegistry = objectRegistry,
                        jsCallbackInvoker = jsCallbackInvoker,
                        bridgeClassLoader = bridgeClassLoader
                    )
                } catch (_: NoSuchMethodException) {
                    null
                }
            if (staticMethodMatch != null) {
                validateJavaBridgeMethodInvocation(
                    targetClass = clazz,
                    instance = null,
                    method = staticMethodMatch.method,
                    args = staticMethodMatch.args
                )
                return@runBridgeCall staticMethodMatch.method.invoke(null, *staticMethodMatch.args)
            }

            val fallbackInstance =
                findStaticFallbackInstance(clazz)
                    ?: throw NoSuchMethodException("static method '$normalizedMethodName' not found on ${clazz.name}")
            val fallbackMethodMatch =
                selectMethod(
                    clazz = fallbackInstance.javaClass,
                    methodName = normalizedMethodName,
                    rawArgs = rawArgs,
                    staticOnly = false,
                    objectRegistry = objectRegistry,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                )
            validateJavaBridgeMethodInvocation(
                targetClass = fallbackInstance.javaClass,
                instance = fallbackInstance,
                method = fallbackMethodMatch.method,
                args = fallbackMethodMatch.args
            )
            fallbackMethodMatch.method.invoke(fallbackInstance, *fallbackMethodMatch.args)
        }
    }

    fun callInstance(
        instanceHandle: String,
        methodName: String,
        argsJson: String,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker? = null,
        bridgeClassLoader: ClassLoader? = null
    ): String {
        return runBridgeCall(objectRegistry) {
            val instance = requireInstance(instanceHandle, objectRegistry)
            val clazz = instance.javaClass
            val normalizedMethodName = methodName.trim()
            require(normalizedMethodName.isNotEmpty()) { "method name is required" }

            val rawArgs = parseArgsJson(argsJson, objectRegistry)
            val methodMatch =
                selectMethod(
                    clazz = clazz,
                    methodName = normalizedMethodName,
                    rawArgs = rawArgs,
                    staticOnly = false,
                    objectRegistry = objectRegistry,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                )
            validateJavaBridgeMethodInvocation(
                targetClass = clazz,
                instance = instance,
                method = methodMatch.method,
                args = methodMatch.args
            )
            methodMatch.method.invoke(instance, *methodMatch.args)
        }
    }

    fun callStaticSuspend(
        className: String,
        methodName: String,
        argsJson: String,
        objectRegistry: ConcurrentHashMap<String, Any>,
        callback: (String) -> Unit,
        jsCallbackInvoker: JsInterfaceCallbackInvoker? = null,
        bridgeClassLoader: ClassLoader? = null
    ) {
        val callbackCompleted = AtomicBoolean(false)
        val complete: (String) -> Unit = { result ->
            if (callbackCompleted.compareAndSet(false, true)) {
                callback(result)
            }
        }
        try {
            callSuspendInternal(
                targetClass = loadClass(className, bridgeClassLoader),
                instance = null,
                methodName = methodName,
                argsJson = argsJson,
                staticOnly = true,
                objectRegistry = objectRegistry,
                callback = complete,
                jsCallbackInvoker = jsCallbackInvoker,
                bridgeClassLoader = bridgeClassLoader
            )
        } catch (e: Exception) {
            complete(failure(describeThrowable(e)))
        }
    }

    fun callInstanceSuspend(
        instanceHandle: String,
        methodName: String,
        argsJson: String,
        objectRegistry: ConcurrentHashMap<String, Any>,
        callback: (String) -> Unit,
        jsCallbackInvoker: JsInterfaceCallbackInvoker? = null,
        bridgeClassLoader: ClassLoader? = null
    ) {
        val callbackCompleted = AtomicBoolean(false)
        val complete: (String) -> Unit = { result ->
            if (callbackCompleted.compareAndSet(false, true)) {
                callback(result)
            }
        }
        try {
            val instance = requireInstance(instanceHandle, objectRegistry)
            callSuspendInternal(
                targetClass = instance.javaClass,
                instance = instance,
                methodName = methodName,
                argsJson = argsJson,
                staticOnly = false,
                objectRegistry = objectRegistry,
                callback = complete,
                jsCallbackInvoker = jsCallbackInvoker,
                bridgeClassLoader = bridgeClassLoader
            )
        } catch (e: Exception) {
            complete(failure(describeThrowable(e)))
        }
    }

    fun getStaticField(
        className: String,
        fieldName: String,
        objectRegistry: ConcurrentHashMap<String, Any>,
        bridgeClassLoader: ClassLoader? = null
    ): String {
        return runBridgeCall(objectRegistry) {
            val clazz = loadClass(className, bridgeClassLoader)
            val normalizedFieldName = fieldName.trim()
            require(normalizedFieldName.isNotEmpty()) { "field name is required" }
            requireJavaBridgeStaticFieldAllowed(clazz, normalizedFieldName)

            val field = findField(clazz, normalizedFieldName, staticOnly = true)
            if (field != null) {
                return@runBridgeCall field.get(null)
            }

            val getter = findGetter(clazz, normalizedFieldName, staticOnly = true)
            if (getter != null) {
                return@runBridgeCall getter.invoke(null)
            }

            val fallbackInstance = findStaticFallbackInstance(clazz)
            if (fallbackInstance != null) {
                val fallbackField = findField(fallbackInstance.javaClass, normalizedFieldName, staticOnly = false)
                if (fallbackField != null) {
                    return@runBridgeCall fallbackField.get(fallbackInstance)
                }

                val fallbackGetter =
                    findGetter(fallbackInstance.javaClass, normalizedFieldName, staticOnly = false)
                if (fallbackGetter != null) {
                    return@runBridgeCall fallbackGetter.invoke(fallbackInstance)
                }
            }

            throw NoSuchFieldException("static field/property '$normalizedFieldName' not found on ${clazz.name}")
        }
    }

    fun setStaticField(
        className: String,
        fieldName: String,
        @Suppress("UNUSED_PARAMETER") valueJson: String,
        objectRegistry: ConcurrentHashMap<String, Any>,
        @Suppress("UNUSED_PARAMETER") jsCallbackInvoker: JsInterfaceCallbackInvoker? = null,
        bridgeClassLoader: ClassLoader? = null
    ): String {
        return runBridgeCall(objectRegistry) {
            val clazz = loadClass(className, bridgeClassLoader)
            val normalizedFieldName = fieldName.trim()
            require(normalizedFieldName.isNotEmpty()) { "field name is required" }
            throw IllegalArgumentException(
                "Java bridge writes to static field '${clazz.name}.$normalizedFieldName' are not allowed"
            )
        }
    }

    fun getInstanceField(
        instanceHandle: String,
        fieldName: String,
        objectRegistry: ConcurrentHashMap<String, Any>
    ): String {
        return runBridgeCall(objectRegistry) {
            val instance = requireInstance(instanceHandle, objectRegistry)
            val clazz = instance.javaClass
            val normalizedFieldName = fieldName.trim()
            require(normalizedFieldName.isNotEmpty()) { "field name is required" }
            throw NoSuchFieldException(
                "Java bridge instance field '${clazz.name}.$normalizedFieldName' is not allowed"
            )
        }
    }

    fun hasInstanceMethod(
        instanceHandle: String,
        methodName: String,
        objectRegistry: ConcurrentHashMap<String, Any>
    ): String {
        return runBridgeCall(objectRegistry) {
            val instance = requireInstance(instanceHandle, objectRegistry)
            val clazz = instance.javaClass
            val normalizedMethodName = methodName.trim()
            require(normalizedMethodName.isNotEmpty()) { "method name is required" }
            hasMethod(clazz, normalizedMethodName, staticOnly = false)
        }
    }

    fun setInstanceField(
        instanceHandle: String,
        fieldName: String,
        @Suppress("UNUSED_PARAMETER") valueJson: String,
        objectRegistry: ConcurrentHashMap<String, Any>,
        @Suppress("UNUSED_PARAMETER") jsCallbackInvoker: JsInterfaceCallbackInvoker? = null,
        @Suppress("UNUSED_PARAMETER") bridgeClassLoader: ClassLoader? = null
    ): String {
        return runBridgeCall(objectRegistry) {
            val instance = requireInstance(instanceHandle, objectRegistry)
            val clazz = instance.javaClass
            val normalizedFieldName = fieldName.trim()
            require(normalizedFieldName.isNotEmpty()) { "field name is required" }
            throw IllegalArgumentException(
                "Java bridge writes to instance field '${clazz.name}.$normalizedFieldName' are not allowed"
            )
        }
    }

    fun releaseInstance(instanceHandle: String, objectRegistry: ConcurrentHashMap<String, Any>): String {
        return runBridgeCall(objectRegistry) {
            val handle = instanceHandle.trim()
            require(handle.isNotEmpty()) { "instance handle is required" }
            objectRegistry.remove(handle) != null
        }
    }

    private fun ensureJsInterfaceLifecycleWorker() {
        if (!jsInterfaceLifecycleWorkerStarted.compareAndSet(false, true)) {
            return
        }

        Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val reference = jsInterfaceProxyReferenceQueue.remove() as? JsInterfaceProxyReference ?: continue
                    jsInterfaceProxyReferences.remove(reference)
                    releaseCollectedJsInterfaceProxy(reference)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to release collected JS interface proxy: ${e.message}", e)
                }
            }
        }.apply {
            isDaemon = true
            name = "OperitJsInterfaceRelease"
        }.start()
    }

    private fun trackJsInterfaceProxy(
        proxy: Any,
        callbackInvoker: JsInterfaceCallbackInvoker,
        jsObjectIds: Collection<String>
    ) {
        val normalizedIds =
            jsObjectIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        if (normalizedIds.isEmpty()) {
            return
        }

        ensureJsInterfaceLifecycleWorker()
        synchronized(jsInterfaceLifecycleLock) {
            if (!jsInterfaceReleaseInvokers.containsKey(callbackInvoker)) {
                return
            }
            val counts = jsInterfaceReferenceCounts.getOrPut(callbackInvoker) { LinkedHashMap() }
            normalizedIds.forEach { jsObjectId ->
                counts[jsObjectId] = (counts[jsObjectId] ?: 0) + 1
            }
        }

        jsInterfaceProxyReferences.add(
            JsInterfaceProxyReference(
                referent = proxy,
                callbackInvoker = callbackInvoker,
                jsObjectIds = normalizedIds
            )
        )
    }

    private fun releaseCollectedJsInterfaceProxy(reference: JsInterfaceProxyReference) {
        val idsToRelease = mutableListOf<String>()
        val releaseInvoker: JsInterfaceReleaseInvoker?

        synchronized(jsInterfaceLifecycleLock) {
            val counts = jsInterfaceReferenceCounts[reference.callbackInvoker]
            releaseInvoker = jsInterfaceReleaseInvokers[reference.callbackInvoker]
            if (counts == null) {
                return
            }

            reference.jsObjectIds.forEach { jsObjectId ->
                val remaining = (counts[jsObjectId] ?: 0) - 1
                if (remaining <= 0) {
                    counts.remove(jsObjectId)
                    if (releaseInvoker != null) {
                        idsToRelease += jsObjectId
                    }
                } else {
                    counts[jsObjectId] = remaining
                }
            }

            if (counts.isEmpty()) {
                jsInterfaceReferenceCounts.remove(reference.callbackInvoker)
            }
        }

        if (releaseInvoker == null) {
            return
        }

        idsToRelease.forEach { jsObjectId ->
            try {
                releaseInvoker.invoke(jsObjectId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to auto-release JS interface object $jsObjectId: ${e.message}", e)
            }
        }
    }

    private fun collectJsInterfaceObjectIds(value: Any?, output: MutableSet<String>) {
        when (value) {
            is JsInterfaceBinding -> {
                val normalized = value.jsObjectId.trim()
                if (normalized.isNotEmpty()) {
                    output += normalized
                }
            }
            is Map<*, *> -> value.values.forEach { collectJsInterfaceObjectIds(it, output) }
            is Iterable<*> -> value.forEach { collectJsInterfaceObjectIds(it, output) }
            is Array<*> -> value.forEach { collectJsInterfaceObjectIds(it, output) }
        }
    }

    private inline fun runBridgeCall(
        objectRegistry: ConcurrentHashMap<String, Any>,
        block: () -> Any?
    ): String {
        return try {
            val value = block.invoke()
            success(value = value, objectRegistry = objectRegistry)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            AppLogger.e(TAG, "Java bridge invocation error: ${cause.message}", cause)
            failure(describeThrowable(cause))
        } catch (e: Exception) {
            val shouldLog =
                e !is NoSuchFieldException &&
                    e !is NoSuchMethodException
            if (shouldLog) {
                AppLogger.e(TAG, "Java bridge error: ${e.message}", e)
            }
            failure(describeThrowable(e))
        }
    }

    private fun describeThrowable(error: Throwable): String {
        val parts = ArrayList<String>()
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current) && parts.size < 6) {
            val label = current.message?.trim().takeUnless { it.isNullOrEmpty() }
                ?: current.javaClass.name
            parts += label
            current = current.cause
        }
        return parts.joinToString(separator = " | caused by: ")
    }

    private fun callSuspendInternal(
        targetClass: Class<*>,
        instance: Any?,
        methodName: String,
        argsJson: String,
        staticOnly: Boolean,
        objectRegistry: ConcurrentHashMap<String, Any>,
        callback: (String) -> Unit,
        jsCallbackInvoker: JsInterfaceCallbackInvoker?,
        bridgeClassLoader: ClassLoader?
    ) {
        val normalizedMethodName = methodName.trim()
        if (normalizedMethodName.isEmpty()) {
            callback(failure("method name is required"))
            return
        }

        val rawArgs = parseArgsJson(argsJson, objectRegistry)
        val candidates =
            targetClass.methods.filter { method ->
                method.name == normalizedMethodName &&
                    Modifier.isStatic(method.modifiers) == staticOnly &&
                    isJavaBridgeMethodAllowed(targetClass, method) &&
                    method.parameterTypes.isNotEmpty() &&
                    Continuation::class.java.isAssignableFrom(method.parameterTypes.last())
            }

        var best: MethodMatch? = null
        for (method in candidates) {
            val parameterTypes = method.parameterTypes
            val argParamTypes = parameterTypes.copyOfRange(0, parameterTypes.size - 1)
            val converted =
                convertArguments(
                    parameterTypes = argParamTypes,
                    isVarArgs = method.isVarArgs,
                    rawArgs = rawArgs,
                    objectRegistry = objectRegistry,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                ) ?: continue

            val match = MethodMatch(method, converted.first, converted.second)
            if (best == null || match.score < best.score) {
                best = match
            }
        }

        if ((candidates.isEmpty() || best == null) && staticOnly) {
            val fallbackInstance = findStaticFallbackInstance(targetClass)
            if (fallbackInstance != null) {
                callSuspendInternal(
                    targetClass = fallbackInstance.javaClass,
                    instance = fallbackInstance,
                    methodName = methodName,
                    argsJson = argsJson,
                    staticOnly = false,
                    objectRegistry = objectRegistry,
                    callback = callback,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                )
                return
            }
        }
        if (candidates.isEmpty()) {
            val callType = if (staticOnly) "static" else "instance"
            callback(failure("$callType suspend method '$normalizedMethodName' not found on ${targetClass.name}"))
            return
        }

        val selected = best
        if (selected == null) {
            callback(
                failure("no suspend method '$normalizedMethodName' matched on ${targetClass.name} with ${rawArgs.size} args")
            )
            return
        }

        val completed = AtomicBoolean(false)
        val continuation =
            object : Continuation<Any?> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Any?>) {
                    val payload =
                        if (result.isSuccess) {
                            successOrFailure(result.getOrNull(), objectRegistry)
                        } else {
                            val error = result.exceptionOrNull()
                            failure(error?.let(::describeThrowable) ?: "unknown error")
                        }
                    if (completed.compareAndSet(false, true)) {
                        callback(payload)
                    }
                }
            }

        try {
            validateJavaBridgeMethodInvocation(
                targetClass = targetClass,
                instance = instance,
                method = selected.method,
                args = selected.args
            )
            val argsWithContinuation = selected.args + continuation
            val outcome = selected.method.invoke(instance, *argsWithContinuation)
            if (outcome !== COROUTINE_SUSPENDED) {
                val payload = successOrFailure(outcome, objectRegistry)
                if (completed.compareAndSet(false, true)) {
                    callback(payload)
                }
            }
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            if (completed.compareAndSet(false, true)) {
                callback(failure(cause.message ?: cause.javaClass.name))
            }
        } catch (e: Exception) {
            if (completed.compareAndSet(false, true)) {
                callback(failure(e.message ?: e.javaClass.name))
            }
        }
    }

    private fun successOrFailure(
        value: Any?,
        objectRegistry: ConcurrentHashMap<String, Any>
    ): String {
        return try {
            success(value, objectRegistry)
        } catch (e: Exception) {
            failure(describeThrowable(e))
        }
    }

    private fun success(value: Any?, objectRegistry: ConcurrentHashMap<String, Any>): String {
        return withBridgeHandleRegistrationTransaction(objectRegistry) { transaction ->
            val payloadValue =
                when (value) {
                    is ConstructedJavaObject ->
                        exposeConstructedJavaObject(value.value, objectRegistry, transaction)
                    else -> {
                        validateBridgeReturnValue(value)
                        toJsonCompatibleValueUnchecked(
                            value = value,
                            objectRegistry = objectRegistry,
                            bridgeAware = true,
                            transaction = transaction
                        )
                    }
                }
            JSONObject()
                .put("success", true)
                .put("data", payloadValue)
                .toString()
        }
    }

    private fun failure(message: String): String {
        return buildJsonObject {
            put("success", JsonPrimitive(false))
            put("message", JsonPrimitive(message))
        }.toString()
    }

    private fun loadClass(className: String, classLoader: ClassLoader? = null): Class<*> {
        val normalized = className.trim()
        require(normalized.isNotEmpty()) { "class name is required" }
        requireJavaBridgeClassAllowed(normalized)
        return when (normalized) {
            "boolean" -> java.lang.Boolean.TYPE
            "byte" -> java.lang.Byte.TYPE
            "short" -> java.lang.Short.TYPE
            "int" -> java.lang.Integer.TYPE
            "long" -> java.lang.Long.TYPE
            "float" -> java.lang.Float.TYPE
            "double" -> java.lang.Double.TYPE
            "char" -> java.lang.Character.TYPE
            "void" -> java.lang.Void.TYPE
            else -> {
                if (classLoader == null) {
                    Class.forName(normalized)
                } else {
                    try {
                        Class.forName(normalized, false, classLoader)
                    } catch (_: ClassNotFoundException) {
                        Class.forName(normalized)
                    }
                }
            }
        }
    }

    private fun requireInstance(
        instanceHandle: String,
        objectRegistry: ConcurrentHashMap<String, Any>
    ): Any {
        val handle = instanceHandle.trim()
        require(handle.isNotEmpty()) { "instance handle is required" }
        val instance = objectRegistry[handle]
            ?: throw IllegalArgumentException("instance handle not found or expired: $handle")
        requireJavaBridgeClassAllowed(instance.javaClass.name)
        return instance
    }

    private fun requireJavaBridgeClassAllowed(className: String) {
        val normalized = className.trim()
        val allowed =
            normalized in allowedClassNames ||
                normalized == "boolean" ||
                normalized == "byte" ||
                normalized == "short" ||
                normalized == "int" ||
                normalized == "long" ||
                normalized == "float" ||
                normalized == "double" ||
                normalized == "char" ||
                normalized == "void"
        require(allowed) {
            "Java bridge access to class '$normalized' is not allowed"
        }
    }

    internal fun isJavaBridgeConstructorAllowed(constructor: Constructor<*>): Boolean {
        val className = constructor.declaringClass.name
        val parameterNames = constructor.parameterTypes.map(Class<*>::getName)
        if (className in primitiveWrapperClassNames) {
            return parameterNames.size == 1 &&
                (constructor.parameterTypes.single().isPrimitive ||
                    parameterNames.single() == "java.lang.String")
        }
        return when (className) {
            "java.lang.String" ->
                parameterNames in
                    setOf(
                        emptyList(),
                        listOf("java.lang.String"),
                        listOf(CharArray::class.java.name),
                        listOf(ByteArray::class.java.name)
                    )
            "java.lang.StringBuilder" ->
                parameterNames in
                    setOf(
                        emptyList(),
                        listOf("java.lang.String")
                    )
            "java.util.ArrayList",
            "java.util.HashSet",
            "java.util.LinkedHashSet" ->
                parameterNames in
                    setOf(
                        emptyList(),
                        listOf("java.util.Collection")
                    )
            "java.util.HashMap",
            "java.util.LinkedHashMap" ->
                parameterNames in
                    setOf(
                        emptyList(),
                        listOf("java.util.Map")
                    )
            "java.util.UUID" -> parameterNames == listOf("long", "long")
            else -> false
        }
    }

    internal fun isJavaBridgeMethodAllowed(targetClass: Class<*>, method: Method): Boolean {
        val signature =
            methodSignature(
                targetClass.name,
                method.name,
                method.parameterTypes.map { it.name }
            )
        if (
            method.parameterTypes.any { parameter -> parameter == CharSequence::class.java } &&
                signature !in safeStringCharSequenceMethodSignatures
        ) {
            return false
        }
        return isJavaBridgeMethodSignatureAllowed(
            className = targetClass.name,
            methodName = method.name,
            parameterTypeNames = method.parameterTypes.map { it.name }
        )
    }

    internal fun isJavaBridgeMethodSignatureAllowed(
        className: String,
        methodName: String,
        parameterTypeNames: List<String>
    ): Boolean {
        if (methodName !in allowedMethodNamesByClass[className].orEmpty()) return false
        val signature = "$methodName(${parameterTypeNames.joinToString(",")})"
        return signature in allowedMethodSignaturesByClass[className].orEmpty()
    }

    private fun requireJavaBridgeStaticFieldAllowed(clazz: Class<*>, fieldName: String) {
        require(fieldName in allowedStaticFieldNamesByClass[clazz.name].orEmpty()) {
            "Java bridge access to static field '${clazz.name}.$fieldName' is not allowed"
        }
    }

    private fun requireJavaBridgeInterfaceProxyAllowed(targetType: Class<*>) {
        require(targetType.isInterface && targetType.name in allowedInterfaceProxyClassNames) {
            "Java bridge interface proxy '${targetType.name}' is not allowed"
        }
    }

    internal fun validateBridgeInputJsonText(rawJson: String) {
        require(rawJson.length <= MAX_BRIDGE_INPUT_JSON_CHARS) {
            "Java bridge input exceeds the JSON text limit ($MAX_BRIDGE_INPUT_JSON_CHARS characters)"
        }
        var depth = 0
        var quoteCharacter: Char? = null
        var escaped = false
        var scannedElements = 0
        val containerTypes = mutableListOf<Char>()
        val arrayExpectingValue = mutableListOf<Boolean>()

        fun consumeElement() {
            scannedElements += 1
            require(scannedElements <= MAX_BRIDGE_SERIALIZATION_ELEMENTS) {
                "Java bridge input exceeds the element limit " +
                    "($MAX_BRIDGE_SERIALIZATION_ELEMENTS)"
            }
        }

        rawJson.forEachIndexed { index, character ->
            if (quoteCharacter != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quoteCharacter -> quoteCharacter = null
                }
            } else {
                require(
                    character != '\'' &&
                        character != ';' &&
                        character != '=' &&
                        character != '#' &&
                        !(
                            character == '/' &&
                                (rawJson.getOrNull(index + 1) == '/' || rawJson.getOrNull(index + 1) == '*')
                        )
                ) {
                    "Java bridge input uses a non-canonical JSON extension"
                }
                val currentIndex = containerTypes.lastIndex
                if (
                    currentIndex >= 0 &&
                        containerTypes[currentIndex] == '[' &&
                        arrayExpectingValue[currentIndex] &&
                        !character.isWhitespace() &&
                        character != ']' &&
                        character != ','
                ) {
                    consumeElement()
                    arrayExpectingValue[currentIndex] = false
                }
                when (character) {
                    '"' -> quoteCharacter = character
                    '[', '{' -> {
                        depth += 1
                        require(depth <= MAX_BRIDGE_SERIALIZATION_DEPTH) {
                            "Java bridge input exceeds the nesting depth limit " +
                                "($MAX_BRIDGE_SERIALIZATION_DEPTH)"
                        }
                        containerTypes.add(character)
                        arrayExpectingValue.add(character == '[')
                    }
                    ']', '}' -> if (depth > 0) {
                        depth -= 1
                        containerTypes.removeAt(containerTypes.lastIndex)
                        arrayExpectingValue.removeAt(arrayExpectingValue.lastIndex)
                    }
                    ',' -> {
                        val index = containerTypes.lastIndex
                        if (index >= 0 && containerTypes[index] == '[') {
                            if (arrayExpectingValue[index]) {
                                consumeElement()
                            }
                            arrayExpectingValue[index] = true
                        }
                    }
                    ':' -> {
                        val index = containerTypes.lastIndex
                        if (index >= 0 && containerTypes[index] == '{') {
                            consumeElement()
                        }
                    }
                }
            }
        }
    }

    internal fun validateJavaBridgeConstructorInvocation(
        constructor: Constructor<*>,
        args: Array<Any?>
    ) {
        require(isJavaBridgeConstructorAllowed(constructor)) {
            "Java bridge constructor '${constructor.declaringClass.name}${constructor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }}' is not allowed"
        }
        validateBridgeReturnValue(args)
        if (constructor.declaringClass == StringBuilder::class.java) {
            val initialLength = (args.firstOrNull() as? CharSequence)?.length ?: 0
            require(initialLength <= MAX_BRIDGE_STRING_BUILDER_CHARS) {
                "Java bridge StringBuilder exceeds the character limit " +
                    "($MAX_BRIDGE_STRING_BUILDER_CHARS)"
            }
        }
    }

    internal fun validateJavaBridgeMethodInvocation(
        targetClass: Class<*>,
        instance: Any?,
        method: Method,
        args: Array<Any?>
    ) {
        require(isJavaBridgeMethodAllowed(targetClass, method)) {
            "Java bridge method '${targetClass.name}.${method.name}' is not allowed"
        }
        val isCollectionsAddAll =
            targetClass == Collections::class.java && method.name == "addAll"
        if (isCollectionsAddAll) {
            // These are two already-bounded bridge inputs. Validating each graph separately avoids
            // rejecting a full Set merely because the wrapper array adds bookkeeping elements.
            args.forEach(::validateBridgeReturnValue)
        } else {
            validateBridgeReturnValue(args)
        }
        if (targetClass == Collections::class.java) {
            validateCollectionsMutationReferences(method, args)
        }
        when (instance) {
            is StringBuilder -> validateStringBuilderInvocation(instance, method, args)
            is Map<*, *> -> {
                validateMapMutationReferences(instance, method, args)
                validateMapGrowth(instance, method, args)
            }
            is Collection<*> -> {
                validateCollectionMutationReferences(instance, method, args)
                validateCollectionGrowth(instance, method, args)
            }
        }
        if (isCollectionsAddAll) {
            val collection = args.firstOrNull() as? Collection<*>
            val addedValues = args.getOrNull(1)?.let(::arrayElements).orEmpty()
            val addedCount =
                if (collection is Set<*>) {
                    val distinctNewValues = HashSet<Any?>()
                    addedValues.count { value ->
                        !collection.contains(value) && distinctNewValues.add(value)
                    }
                } else {
                    addedValues.size
                }
            if (collection != null) {
                require(collection.size.toLong() + addedCount <= MAX_BRIDGE_MUTABLE_CONTAINER_SIZE) {
                    "Java bridge collection exceeds the element limit " +
                        "($MAX_BRIDGE_MUTABLE_CONTAINER_SIZE)"
                }
            }
        }
    }

    private fun validateStringBuilderInvocation(
        builder: StringBuilder,
        method: Method,
        args: Array<Any?>
    ) {
        require(builder.length <= MAX_BRIDGE_STRING_BUILDER_CHARS) {
            "Java bridge StringBuilder exceeds the character limit " +
                "($MAX_BRIDGE_STRING_BUILDER_CHARS)"
        }
        val addedLength =
            when (method.name) {
                "appendCodePoint" ->
                    Character.charCount((args.firstOrNull() as? Number)?.toInt() ?: 0)
                "append" -> appendContribution(method, args)
                "insert" -> insertContribution(method, args)
                "replace" -> {
                    val start = (args.getOrNull(0) as? Number)?.toInt() ?: 0
                    val end = (args.getOrNull(1) as? Number)?.toInt() ?: start
                    val removed =
                        if (start in 0..builder.length && end >= start) {
                            end.coerceAtMost(builder.length) - start
                        } else {
                            0
                        }
                    bridgeTextLength(args.getOrNull(2)) - removed
                }
                else -> 0
            }
        require(builder.length.toLong() + addedLength <= MAX_BRIDGE_STRING_BUILDER_CHARS) {
            "Java bridge StringBuilder exceeds the character limit " +
                "($MAX_BRIDGE_STRING_BUILDER_CHARS)"
        }
    }

    private fun appendContribution(method: Method, args: Array<Any?>): Int {
        if (args.size == 3 && method.parameterTypes.firstOrNull()?.isArray == true) {
            return ((args[2] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
        }
        if (args.size == 3) {
            val start = (args[1] as? Number)?.toInt() ?: 0
            val end = (args[2] as? Number)?.toInt() ?: start
            return (end - start).coerceAtLeast(0)
        }
        return bridgeTextLength(args.firstOrNull())
    }

    private fun insertContribution(method: Method, args: Array<Any?>): Int {
        if (args.size == 4 && method.parameterTypes.getOrNull(1)?.isArray == true) {
            return ((args[3] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
        }
        if (args.size == 4) {
            val start = (args[2] as? Number)?.toInt() ?: 0
            val end = (args[3] as? Number)?.toInt() ?: start
            return (end - start).coerceAtLeast(0)
        }
        return bridgeTextLength(args.getOrNull(1))
    }

    private fun bridgeTextLength(value: Any?): Int {
        return when (value) {
            null -> 4
            is CharSequence -> value.length
            is CharArray -> value.size
            is Char -> 1
            is Boolean, is Number, is UUID -> value.toString().length
            else -> MAX_BRIDGE_STRING_BUILDER_CHARS + 1
        }
    }

    private fun validateCollectionGrowth(
        collection: Collection<*>,
        method: Method,
        args: Array<Any?>
    ) {
        val addedCount =
            when (method.name) {
                "add" ->
                    if (collection is Set<*> && collection.contains(args.firstOrNull())) 0 else 1
                "addAll" -> {
                    val values = args.filterIsInstance<Collection<*>>().firstOrNull().orEmpty()
                    if (collection is Set<*>) {
                        countNewSetElements(collection, values)
                    } else {
                        values.size
                    }
                }
                else -> 0
            }
        require(collection.size.toLong() + addedCount <= MAX_BRIDGE_MUTABLE_CONTAINER_SIZE) {
            "Java bridge collection exceeds the element limit " +
                "($MAX_BRIDGE_MUTABLE_CONTAINER_SIZE)"
        }
    }

    private fun validateCollectionMutationReferences(
        collection: Collection<*>,
        method: Method,
        args: Array<Any?>
    ) {
        val addedValues =
            when (method.name) {
                "add", "set" -> listOf(args.lastOrNull())
                "addAll" ->
                    args.filterIsInstance<Collection<*>>()
                        .firstOrNull()
                        .orEmpty()
                        .toList()
                else -> emptyList()
            }
        requireMutationDoesNotReferenceTarget(collection, addedValues)
    }

    private fun validateCollectionsMutationReferences(method: Method, args: Array<Any?>) {
        val target = args.firstOrNull() as? Collection<*> ?: return
        val insertedValues =
            when (method.name) {
                "addAll" -> args.getOrNull(1)?.let(::arrayElements).orEmpty()
                "copy" -> (args.getOrNull(1) as? Collection<*>)?.toList().orEmpty()
                "fill" -> if (target.isEmpty()) emptyList() else listOf(args.getOrNull(1))
                "replaceAll" ->
                    if (target.contains(args.getOrNull(1))) {
                        listOf(args.getOrNull(2))
                    } else {
                        emptyList()
                    }
                else -> emptyList()
            }
        requireMutationDoesNotReferenceTarget(target, insertedValues)
    }

    private fun validateMapMutationReferences(
        map: Map<*, *>,
        method: Method,
        args: Array<Any?>
    ) {
        val insertedValues =
            when (method.name) {
                "put" -> args.take(2)
                "putIfAbsent" -> {
                    val key = args.firstOrNull()
                    when {
                        map[key] != null -> emptyList()
                        map.containsKey(key) -> listOf(args.getOrNull(1))
                        else -> args.take(2)
                    }
                }
                "putAll" ->
                    (args.firstOrNull() as? Map<*, *>)
                        ?.entries
                        ?.flatMap { listOf(it.key, it.value) }
                        .orEmpty()
                "replace" -> {
                    val key = args.firstOrNull()
                    val willReplace =
                        when (args.size) {
                            2 -> map.containsKey(key)
                            3 -> map.containsKey(key) && map[key] == args.getOrNull(1)
                            else -> false
                        }
                    if (willReplace) listOf(args.lastOrNull()) else emptyList()
                }
                else -> emptyList()
            }
        requireMutationDoesNotReferenceTarget(map, insertedValues)
    }

    private fun requireMutationDoesNotReferenceTarget(
        target: Any,
        insertedValues: Iterable<Any?>
    ) {
        require(insertedValues.none { graphContainsIdentity(it, target) }) {
            "Java bridge mutation would create a cyclic container reference"
        }
    }

    private fun graphContainsIdentity(value: Any?, target: Any): Boolean {
        val visited = IdentityHashMap<Any, Boolean>()

        fun contains(current: Any?): Boolean {
            if (current === target) return true
            if (current == null || current === JSONObject.NULL) return false
            val isContainer =
                current is JSONObject ||
                    current is JSONArray ||
                    current is Map<*, *> ||
                    current is Iterable<*> ||
                    current.javaClass.isArray
            if (!isContainer || visited.put(current, true) != null) return false

            return when (current) {
                is JSONObject -> {
                    val keys = current.keys()
                    var found = false
                    while (keys.hasNext() && !found) {
                        found = contains(current.opt(keys.next()))
                    }
                    found
                }
                is JSONArray ->
                    (0 until current.length()).any { index -> contains(current.opt(index)) }
                is Map<*, *> ->
                    current.entries.any { entry -> contains(entry.key) || contains(entry.value) }
                is Iterable<*> -> current.any(::contains)
                else ->
                    (0 until ReflectArray.getLength(current)).any { index ->
                        contains(ReflectArray.get(current, index))
                    }
            }
        }

        return contains(value)
    }

    private fun countNewSetElements(existing: Set<*>, values: Iterable<*>): Int {
        val pending = HashSet<Any?>()
        values.forEach { value ->
            if (!existing.contains(value)) {
                pending.add(value)
            }
        }
        return pending.size
    }

    private fun validateMapGrowth(
        map: Map<*, *>,
        method: Method,
        args: Array<Any?>
    ) {
        val addedCount =
            when (method.name) {
                "put", "putIfAbsent" -> if (map.containsKey(args.firstOrNull())) 0 else 1
                "putAll" -> (args.firstOrNull() as? Map<*, *>)?.keys?.count { it !in map } ?: 0
                else -> 0
            }
        require(map.size.toLong() + addedCount <= MAX_BRIDGE_MUTABLE_CONTAINER_SIZE) {
            "Java bridge map exceeds the entry limit ($MAX_BRIDGE_MUTABLE_CONTAINER_SIZE)"
        }
    }

    private fun arrayLengthOrZero(value: Any): Int {
        return if (value.javaClass.isArray) ReflectArray.getLength(value) else 0
    }

    private fun arrayElements(value: Any): List<Any?> {
        if (!value.javaClass.isArray) return emptyList()
        return List(ReflectArray.getLength(value)) { index -> ReflectArray.get(value, index) }
    }

    private fun parseArgsJson(
        argsJson: String,
        objectRegistry: ConcurrentHashMap<String, Any>
    ): List<Any?> =
        parseArgsJsonInternal(
            argsJson = argsJson,
            objectRegistry = objectRegistry,
            validateDecodedGraph = true
        )

    private fun parseArgsJsonInternal(
        argsJson: String,
        objectRegistry: ConcurrentHashMap<String, Any>,
        validateDecodedGraph: Boolean
    ): List<Any?> {
        validateBridgeInputJsonText(argsJson)
        val normalized = argsJson.trim()
        if (normalized.isEmpty() || normalized == "undefined" || normalized == "null") {
            return emptyList()
        }
        val raw = JSONTokener(normalized).nextValue()
        require(raw is JSONArray) { "arguments must be a JSON array" }

        val args = ArrayList<Any?>(raw.length())
        for (index in 0 until raw.length()) {
            args.add(
                decodeJsonValue(
                    raw = raw.get(index),
                    objectRegistry = objectRegistry,
                    interpretBridgeMarkers = true
                )
            )
        }
        if (validateDecodedGraph) {
            validateBridgeReturnValue(args)
        }
        return args
    }

    private fun decodeJsonValue(
        raw: Any?,
        objectRegistry: ConcurrentHashMap<String, Any>,
        interpretBridgeMarkers: Boolean
    ): Any? {
        return when (raw) {
            null,
            JSONObject.NULL -> null
            is JSONObject -> {
                if (interpretBridgeMarkers && raw.has(HANDLE_KEY) && raw.has(CLASS_KEY)) {
                    val handle = raw.optString(HANDLE_KEY).trim()
                    if (handle.isEmpty()) {
                        null
                    } else {
                        objectRegistry[handle]
                            ?: throw IllegalArgumentException("instance handle not found or expired: $handle")
                    }
                } else if (
                    interpretBridgeMarkers &&
                        (raw.optBoolean(JS_INTERFACE_KEY, false) || raw.has(JS_OBJECT_ID_KEY)) &&
                        raw.optString(JS_OBJECT_ID_KEY).trim().isNotEmpty()
                ) {
                    val interfaceNames = mutableListOf<String>()
                    val rawInterfaces = raw.opt(JS_INTERFACES_KEY)
                    when (rawInterfaces) {
                        is JSONArray -> {
                            for (i in 0 until rawInterfaces.length()) {
                                val name = rawInterfaces.optString(i).trim()
                                if (name.isNotEmpty()) {
                                    interfaceNames.add(name)
                                }
                            }
                        }
                        is String -> {
                            val name = rawInterfaces.trim()
                            if (name.isNotEmpty()) {
                                interfaceNames.add(name)
                            }
                        }
                    }
                    JsInterfaceBinding(
                        jsObjectId = raw.optString(JS_OBJECT_ID_KEY).trim(),
                        interfaceNames = interfaceNames
                    )
                } else {
                    val map = LinkedHashMap<String, Any?>()
                    raw.keys().forEach { key ->
                        map[key] =
                            decodeJsonValue(
                                raw = raw.opt(key),
                                objectRegistry = objectRegistry,
                                interpretBridgeMarkers = interpretBridgeMarkers
                            )
                    }
                    map
                }
            }
            is JSONArray -> {
                val list = ArrayList<Any?>(raw.length())
                for (index in 0 until raw.length()) {
                    list.add(
                        decodeJsonValue(
                            raw = raw.get(index),
                            objectRegistry = objectRegistry,
                            interpretBridgeMarkers = interpretBridgeMarkers
                        )
                    )
                }
                list
            }
            is String -> if (interpretBridgeMarkers) decodeBridgeString(raw) else raw
            else -> raw
        }
    }

    private fun toJsonCompatibleValue(
        value: Any?,
        objectRegistry: ConcurrentHashMap<String, Any>,
        bridgeAware: Boolean = true
    ): Any {
        validateBridgeReturnValueForMode(value, bridgeAware)
        if (!bridgeAware) {
            return toJsonCompatibleValueUnchecked(
                value = value,
                objectRegistry = objectRegistry,
                bridgeAware = false,
                transaction = null
            )
        }
        return withBridgeHandleRegistrationTransaction(objectRegistry) { transaction ->
            toJsonCompatibleValueUnchecked(
                value = value,
                objectRegistry = objectRegistry,
                bridgeAware = true,
                transaction = transaction
            )
        }
    }

    internal fun validateBridgeReturnValueForMode(value: Any?, bridgeAware: Boolean) {
        if (bridgeAware) {
            validateBridgeReturnValue(value)
        }
    }

    private fun toJsonCompatibleValueUnchecked(
        value: Any?,
        objectRegistry: ConcurrentHashMap<String, Any>,
        bridgeAware: Boolean,
        transaction: BridgeHandleRegistrationTransaction?
    ): Any {
        if (value == null) {
            return JSONObject.NULL
        }

        return when (value) {
            JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> value
            is JSONArray -> value
            is String -> if (bridgeAware) encodeBridgeString(value) else value
            is Boolean -> value
            is Int -> value
            is Long -> value
            is Double -> encodeNonFiniteNumber(value) ?: value
            is Float -> encodeNonFiniteNumber(value.toDouble()) ?: value.toDouble()
            is Number -> value
            is Char -> value.toString()
            is Enum<*> -> value.name
            is Class<*> -> value.name
            is Map<*, *> -> {
                val obj = JSONObject()
                value.entries.forEach { entry ->
                    val key = entry.key?.toString() ?: return@forEach
                    obj.put(
                        key,
                        toJsonCompatibleValueUnchecked(
                            value = entry.value,
                            objectRegistry = objectRegistry,
                            bridgeAware = bridgeAware,
                            transaction = transaction
                        )
                    )
                }
                obj
            }
            is Iterable<*> -> {
                val arr = JSONArray()
                value.forEach { item ->
                    arr.put(
                        toJsonCompatibleValueUnchecked(
                            value = item,
                            objectRegistry = objectRegistry,
                            bridgeAware = bridgeAware,
                            transaction = transaction
                        )
                    )
                }
                arr
            }
            else -> {
                if (value.javaClass.isArray) {
                    val arr = JSONArray()
                    val len = ReflectArray.getLength(value)
                    for (index in 0 until len) {
                        arr.put(
                            toJsonCompatibleValueUnchecked(
                                value = ReflectArray.get(value, index),
                                objectRegistry = objectRegistry,
                                bridgeAware = bridgeAware,
                                transaction = transaction
                            )
                        )
                    }
                    arr
                } else if (value === Unit) {
                    JSONObject.NULL
                } else {
                    if (!bridgeAware) {
                        throw IllegalArgumentException(
                            "value is not JSON-serializable: ${value.javaClass.name}"
                        )
                    }
                    requireJavaBridgeClassAllowed(value.javaClass.name)
                    val handle =
                        registerBridgeObjectHandle(value, objectRegistry, transaction)
                    JSONObject()
                        .put(HANDLE_KEY, handle)
                        .put(CLASS_KEY, value.javaClass.name)
                }
            }
        }
    }

    internal fun validateBridgeReturnValue(value: Any?) {
        val activeContainers = IdentityHashMap<Any, Boolean>()
        var visitedElements = 0
        var visitedScalarChars = 0L

        fun consumeElement() {
            visitedElements += 1
            require(visitedElements <= MAX_BRIDGE_SERIALIZATION_ELEMENTS) {
                "Java bridge result exceeds the serialization element limit " +
                    "($MAX_BRIDGE_SERIALIZATION_ELEMENTS)"
            }
        }

        fun consumeScalarChars(count: Int) {
            visitedScalarChars += count.toLong()
            require(visitedScalarChars <= MAX_BRIDGE_SERIALIZATION_SCALAR_CHARS) {
                "Java bridge result exceeds the serialization scalar text limit " +
                    "($MAX_BRIDGE_SERIALIZATION_SCALAR_CHARS characters)"
            }
        }

        fun visit(current: Any?, depth: Int) {
            require(depth <= MAX_BRIDGE_SERIALIZATION_DEPTH) {
                "Java bridge result exceeds the serialization depth limit " +
                    "($MAX_BRIDGE_SERIALIZATION_DEPTH)"
            }

            val container = current
            when (container) {
                null, JSONObject.NULL -> consumeScalarChars(4)
                is String -> consumeScalarChars(container.length)
                is CharSequence -> consumeScalarChars(container.length)
                is Char -> consumeScalarChars(1)
                is Boolean -> consumeScalarChars(5)
                is Number -> consumeScalarChars(container.toString().length)
                is Enum<*> -> consumeScalarChars(container.name.length)
                is Class<*> -> consumeScalarChars(container.name.length)
                is UUID -> consumeScalarChars(36)
            }
            if (container == null || container === JSONObject.NULL) {
                return
            }
            val isContainer =
                container is JSONObject ||
                    container is JSONArray ||
                    container is Map<*, *> ||
                    container is Iterable<*> ||
                    container.javaClass.isArray
            if (!isContainer) {
                return
            }

            require(activeContainers.put(container, true) == null) {
                "Java bridge result contains a cyclic container reference"
            }
            try {
                when (container) {
                    is JSONObject -> {
                        val keys = container.keys()
                        while (keys.hasNext()) {
                            consumeElement()
                            val key = keys.next()
                            consumeScalarChars(key.length)
                            visit(container.opt(key), depth + 1)
                        }
                    }
                    is JSONArray -> {
                        for (index in 0 until container.length()) {
                            consumeElement()
                            visit(container.opt(index), depth + 1)
                        }
                    }
                    is Map<*, *> -> {
                        container.entries.forEach { entry ->
                            // A Map serializes to one JSONObject property per entry. Count the
                            // entry once, while still traversing both key and value for cycles,
                            // depth and scalar-text budgets (the key is stringified later).
                            consumeElement()
                            visit(entry.key, depth + 1)
                            visit(entry.value, depth + 1)
                        }
                    }
                    is Iterable<*> -> {
                        container.forEach { item ->
                            consumeElement()
                            visit(item, depth + 1)
                        }
                    }
                    else -> {
                        val length = ReflectArray.getLength(container)
                        for (index in 0 until length) {
                            consumeElement()
                            visit(ReflectArray.get(container, index), depth + 1)
                        }
                    }
                }
            } finally {
                activeContainers.remove(container)
            }
        }

        visit(value, 0)
    }

    private fun encodeNonFiniteNumber(value: Double): String? =
        when {
            value.isNaN() -> "${BRIDGE_NUMBER_PREFIX}NaN"
            value == Double.POSITIVE_INFINITY -> "${BRIDGE_NUMBER_PREFIX}Infinity"
            value == Double.NEGATIVE_INFINITY -> "${BRIDGE_NUMBER_PREFIX}-Infinity"
            else -> null
        }

    private fun encodeBridgeString(value: String): String =
        if (value.startsWith(BRIDGE_NUMBER_PREFIX)) {
            BRIDGE_STRING_ESCAPE_PREFIX + value
        } else {
            value
        }

    private fun decodeBridgeString(value: String): Any =
        when {
            value.startsWith(BRIDGE_STRING_ESCAPE_PREFIX) ->
                value.removePrefix(BRIDGE_STRING_ESCAPE_PREFIX)
            value == "${BRIDGE_NUMBER_PREFIX}NaN" -> Double.NaN
            value == "${BRIDGE_NUMBER_PREFIX}Infinity" -> Double.POSITIVE_INFINITY
            value == "${BRIDGE_NUMBER_PREFIX}-Infinity" -> Double.NEGATIVE_INFINITY
            else -> value
        }

    private fun exposeConstructedJavaObject(
        value: Any,
        objectRegistry: ConcurrentHashMap<String, Any>,
        transaction: BridgeHandleRegistrationTransaction
    ): JSONObject {
        requireJavaBridgeClassAllowed(value.javaClass.name)
        val handle = registerBridgeObjectHandle(value, objectRegistry, transaction)
        return JSONObject()
            .put(HANDLE_KEY, handle)
            .put(CLASS_KEY, value.javaClass.name)
    }

    internal fun registerBridgeObjectHandle(
        value: Any,
        objectRegistry: ConcurrentHashMap<String, Any>
    ): String = registerBridgeObjectHandle(value, objectRegistry, transaction = null)

    private fun registerBridgeObjectHandle(
        value: Any,
        objectRegistry: ConcurrentHashMap<String, Any>,
        transaction: BridgeHandleRegistrationTransaction?
    ): String {
        synchronized(objectRegistry) {
            transaction?.handlesByIdentity?.get(value)?.let { return it }
            objectRegistry.entries.firstOrNull { entry -> entry.value === value }?.key?.let { handle ->
                transaction?.handlesByIdentity?.put(value, handle)
                return handle
            }
            require(objectRegistry.size < MAX_BRIDGE_LIVE_OBJECT_HANDLES) {
                "Java bridge exceeds the live object handle limit " +
                    "($MAX_BRIDGE_LIVE_OBJECT_HANDLES)"
            }
            while (true) {
                val handle = UUID.randomUUID().toString()
                if (objectRegistry.putIfAbsent(handle, value) == null) {
                    transaction?.handlesByIdentity?.put(value, handle)
                    transaction?.insertedHandles?.add(handle to value)
                    return handle
                }
            }
        }
    }

    private fun <T> withBridgeHandleRegistrationTransaction(
        objectRegistry: ConcurrentHashMap<String, Any>,
        block: (BridgeHandleRegistrationTransaction) -> T
    ): T {
        val transaction = BridgeHandleRegistrationTransaction()
        var completed = false
        try {
            val result = block(transaction)
            completed = true
            return result
        } finally {
            if (!completed) {
                synchronized(objectRegistry) {
                    transaction.insertedHandles.asReversed().forEach { (handle, value) ->
                        objectRegistry.remove(handle, value)
                    }
                }
            }
        }
    }

    private fun toStructuredJsonValue(
        value: Any?,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker?,
        bridgeClassLoader: ClassLoader?
    ): Any {
        if (value == null) {
            return JSONObject.NULL
        }

        return when (value) {
            JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> {
                val copy = JSONObject()
                value.keys().forEach { key ->
                    copy.put(
                        key,
                        toStructuredJsonValue(
                            value = value.opt(key),
                            objectRegistry = objectRegistry,
                            jsCallbackInvoker = jsCallbackInvoker,
                            bridgeClassLoader = bridgeClassLoader
                        )
                    )
                }
                copy
            }
            is JSONArray -> {
                val copy = JSONArray()
                for (index in 0 until value.length()) {
                    copy.put(
                        toStructuredJsonValue(
                            value = value.opt(index),
                            objectRegistry = objectRegistry,
                            jsCallbackInvoker = jsCallbackInvoker,
                            bridgeClassLoader = bridgeClassLoader
                        )
                    )
                }
                copy
            }
            is Map<*, *> -> {
                val obj = JSONObject()
                value.entries.forEach { entry ->
                    val key = entry.key?.toString() ?: return@forEach
                    obj.put(
                        key,
                        toStructuredJsonValue(
                            value = entry.value,
                            objectRegistry = objectRegistry,
                            jsCallbackInvoker = jsCallbackInvoker,
                            bridgeClassLoader = bridgeClassLoader
                        )
                    )
                }
                obj
            }
            is Iterable<*> -> {
                val arr = JSONArray()
                value.forEach { item ->
                    arr.put(
                        toStructuredJsonValue(
                            value = item,
                            objectRegistry = objectRegistry,
                            jsCallbackInvoker = jsCallbackInvoker,
                            bridgeClassLoader = bridgeClassLoader
                        )
                    )
                }
                arr
            }
            else -> {
                if (value.javaClass.isArray) {
                    val arr = JSONArray()
                    val len = ReflectArray.getLength(value)
                    for (index in 0 until len) {
                        arr.put(
                            toStructuredJsonValue(
                                value = ReflectArray.get(value, index),
                                objectRegistry = objectRegistry,
                                jsCallbackInvoker = jsCallbackInvoker,
                                bridgeClassLoader = bridgeClassLoader
                            )
                        )
                    }
                    arr
                } else {
                    convertArg(
                        rawValue = value,
                        targetType = Any::class.java,
                        objectRegistry = objectRegistry,
                        jsCallbackInvoker = jsCallbackInvoker,
                        bridgeClassLoader = bridgeClassLoader
                    )?.value ?: value
                }
            }
        }
    }

    private fun selectConstructor(
        clazz: Class<*>,
        rawArgs: List<Any?>,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker?,
        bridgeClassLoader: ClassLoader?
    ): ConstructorMatch {
        val candidates = clazz.constructors.filter(::isJavaBridgeConstructorAllowed)
        if (candidates.isEmpty()) {
            throw NoSuchMethodException(buildConstructorUnavailableMessage(clazz))
        }

        var best: ConstructorMatch? = null
        for (constructor in candidates) {
            val converted =
                convertArguments(
                    parameterTypes = constructor.parameterTypes,
                    isVarArgs = constructor.isVarArgs,
                    rawArgs = rawArgs,
                    objectRegistry = objectRegistry,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                ) ?: continue

            val match = ConstructorMatch(constructor, converted.first, converted.second)
            if (best == null || match.score < best.score) {
                best = match
            }
        }

        return best
            ?: throw NoSuchMethodException(
                buildConstructorMismatchMessage(clazz, rawArgs)
            )
    }

    private fun buildConstructorUnavailableMessage(clazz: Class<*>): String {
        val suffix =
            when {
                clazz.isInterface ->
                    " ${clazz.name} is an interface; use Java.implement(\"${clazz.name}\", impl) or pass the callback/object into an API that explicitly expects this interface."
                Modifier.isAbstract(clazz.modifiers) ->
                    " ${clazz.name} is abstract and cannot be instantiated directly."
                else -> ""
            }
        return "no public constructor available for ${clazz.name}$suffix"
    }

    private fun buildConstructorMismatchMessage(clazz: Class<*>, rawArgs: List<Any?>): String {
        val hasJsInterfaceArg = rawArgs.any { it is JsInterfaceBinding }
        val hint =
            if (hasJsInterfaceArg) {
                " If one of these arguments is meant to satisfy a Java interface, prefer Java.implement(\"interface.name\", impl) so overload resolution is explicit."
            } else {
                ""
            }
        return "no constructor matched for ${clazz.name} with ${rawArgs.size} args$hint"
    }

    private fun selectMethod(
        clazz: Class<*>,
        methodName: String,
        rawArgs: List<Any?>,
        staticOnly: Boolean,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker?,
        bridgeClassLoader: ClassLoader?
    ): MethodMatch {
        val candidates =
            clazz.methods.filter { method ->
                method.name == methodName &&
                    Modifier.isStatic(method.modifiers) == staticOnly &&
                    isJavaBridgeMethodAllowed(clazz, method)
            }

        if (candidates.isEmpty()) {
            val callType = if (staticOnly) "static" else "instance"
            throw NoSuchMethodException("$callType method '$methodName' not found on ${clazz.name}")
        }

        var best: MethodMatch? = null
        for (method in candidates) {
            val converted =
                convertArguments(
                    parameterTypes = method.parameterTypes,
                    isVarArgs = method.isVarArgs,
                    rawArgs = rawArgs,
                    objectRegistry = objectRegistry,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                ) ?: continue

            val match = MethodMatch(method, converted.first, converted.second)
            if (best == null || match.score < best.score) {
                best = match
            }
        }

        return best
            ?: throw NoSuchMethodException(
                "no method '$methodName' matched on ${clazz.name} with ${rawArgs.size} args"
            )
    }

    private fun convertArguments(
        parameterTypes: Array<Class<*>>,
        isVarArgs: Boolean,
        rawArgs: List<Any?>,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker?,
        bridgeClassLoader: ClassLoader?
    ): Pair<Array<Any?>, Int>? {
        if (!isVarArgs) {
            if (parameterTypes.size != rawArgs.size) {
                return null
            }
            val out = arrayOfNulls<Any?>(parameterTypes.size)
            var score = 0
            for (index in parameterTypes.indices) {
                val converted =
                    convertArg(
                        rawValue = rawArgs[index],
                        targetType = parameterTypes[index],
                        objectRegistry = objectRegistry,
                        jsCallbackInvoker = jsCallbackInvoker,
                        bridgeClassLoader = bridgeClassLoader
                    ) ?: return null
                out[index] = converted.value
                score += converted.score
            }
            return Pair(out, score)
        }

        val fixedCount = parameterTypes.size - 1
        if (rawArgs.size < fixedCount) {
            return null
        }

        val out = arrayOfNulls<Any?>(parameterTypes.size)
        var score = 2

        for (index in 0 until fixedCount) {
            val converted =
                convertArg(
                    rawValue = rawArgs[index],
                    targetType = parameterTypes[index],
                    objectRegistry = objectRegistry,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                ) ?: return null
            out[index] = converted.value
            score += converted.score
        }

        val varargArrayType = parameterTypes.last()
        val componentType = varargArrayType.componentType ?: return null
        val rawVarargValues =
            if (
                rawArgs.size == parameterTypes.size &&
                rawArgs.lastOrNull() is List<*> &&
                !componentType.isArray
            ) {
                @Suppress("UNCHECKED_CAST")
                rawArgs.subList(0, fixedCount) + (rawArgs.last() as List<Any?>)
            } else {
                rawArgs
            }
        val varargLength = rawVarargValues.size - fixedCount
        val varargArray = ReflectArray.newInstance(componentType, varargLength)
        for (offset in 0 until varargLength) {
            val converted =
                convertArg(
                    rawValue = rawVarargValues[fixedCount + offset],
                    targetType = componentType,
                    objectRegistry = objectRegistry,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                ) ?: return null
            ReflectArray.set(varargArray, offset, converted.value)
            score += converted.score
        }
        out[out.lastIndex] = varargArray

        return Pair(out, score)
    }

    private fun convertArg(
        rawValue: Any?,
        targetType: Class<*>,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker?,
        bridgeClassLoader: ClassLoader?
    ): ConvertedArg? {
        if (rawValue == null) {
            return if (targetType.isPrimitive) {
                null
            } else {
                ConvertedArg(null, 4)
            }
        }

        if (rawValue is JsInterfaceBinding) {
            val proxy =
                createJsInterfaceProxy(
                    binding = rawValue,
                    targetType = targetType,
                    objectRegistry = objectRegistry,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                )
            if (proxy != null) {
                return ConvertedArg(proxy, 2)
            }
        }

        val wrapper = primitiveWrapperMap[targetType] ?: targetType

        if (wrapper == Any::class.java || wrapper == Object::class.java) {
            if (rawValue is JsInterfaceBinding) {
                val proxy =
                    createJsInterfaceProxy(
                        binding = rawValue,
                        targetType = wrapper,
                        objectRegistry = objectRegistry,
                        jsCallbackInvoker = jsCallbackInvoker,
                        bridgeClassLoader = bridgeClassLoader
                    )
                if (proxy != null) {
                    return ConvertedArg(proxy, 3)
                }
            }
            return ConvertedArg(rawValue, 4)
        }

        if (
            rawValue is Map<*, *> &&
                wrapper.isInterface &&
                !Map::class.java.isAssignableFrom(wrapper) &&
                !Collection::class.java.isAssignableFrom(wrapper)
        ) {
            val proxy =
                createJsInterfaceProxyFromMap(
                    rawValue = rawValue,
                    targetType = wrapper,
                    objectRegistry = objectRegistry,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                )
            if (proxy != null) {
                return ConvertedArg(proxy, 3)
            }
        }

        if (wrapper.isInstance(rawValue)) {
            val score =
                when (wrapper) {
                    Any::class.java -> 4
                    Number::class.java -> 1
                    else -> 0
                }
            return ConvertedArg(rawValue, score)
        }

        if (wrapper == String::class.java) {
            return ConvertedArg(rawValue.toString(), 3)
        }

        if (wrapper == java.lang.Boolean::class.java) {
            return convertToBoolean(rawValue)
        }

        if (Number::class.java.isAssignableFrom(wrapper)) {
            return convertToNumber(rawValue, wrapper)
        }

        if (wrapper == java.lang.Character::class.java) {
            return convertToChar(rawValue)
        }

        if (wrapper.isEnum) {
            return convertToEnum(rawValue, wrapper)
        }

        if (wrapper == Class::class.java && rawValue is String) {
            return try {
                ConvertedArg(loadClass(rawValue, bridgeClassLoader), 4)
            } catch (_: Exception) {
                null
            }
        }

        if (wrapper == JSONObject::class.java) {
            return when (rawValue) {
                is Map<*, *> -> {
                    val obj =
                        toStructuredJsonValue(
                            value = rawValue,
                            objectRegistry = objectRegistry,
                            jsCallbackInvoker = jsCallbackInvoker,
                            bridgeClassLoader = bridgeClassLoader
                        ) as JSONObject
                    ConvertedArg(obj, 5)
                }
                is String -> {
                    try {
                        ConvertedArg(JSONObject(rawValue), 6)
                    } catch (_: Exception) {
                        null
                    }
                }
                else -> null
            }
        }

        if (wrapper == JSONArray::class.java) {
            return when (rawValue) {
                is List<*> -> {
                    val arr =
                        toStructuredJsonValue(
                            value = rawValue,
                            objectRegistry = objectRegistry,
                            jsCallbackInvoker = jsCallbackInvoker,
                            bridgeClassLoader = bridgeClassLoader
                        ) as JSONArray
                    ConvertedArg(arr, 5)
                }
                is String -> {
                    try {
                        ConvertedArg(JSONArray(rawValue), 6)
                    } catch (_: Exception) {
                        null
                    }
                }
                else -> null
            }
        }

        if (wrapper.isArray) {
            if (rawValue is List<*>) {
                val componentType = wrapper.componentType
                val arr = ReflectArray.newInstance(componentType, rawValue.size)
                var score = 5
                for (index in rawValue.indices) {
                    val converted =
                        convertArg(
                            rawValue = rawValue[index],
                            targetType = componentType,
                            objectRegistry = objectRegistry,
                            jsCallbackInvoker = jsCallbackInvoker,
                            bridgeClassLoader = bridgeClassLoader
                        ) ?: return null
                    ReflectArray.set(arr, index, converted.value)
                    score += converted.score
                }
                return ConvertedArg(arr, score)
            }
            if (rawValue.javaClass.isArray && wrapper.isAssignableFrom(rawValue.javaClass)) {
                return ConvertedArg(rawValue, 2)
            }
        }

        if (Collection::class.java.isAssignableFrom(wrapper) && rawValue is List<*>) {
            return ConvertedArg(rawValue.toMutableList(), 7)
        }

        if (Map::class.java.isAssignableFrom(wrapper) && rawValue is Map<*, *>) {
            return ConvertedArg(rawValue, 6)
        }

        if (wrapper.isAssignableFrom(rawValue.javaClass)) {
            return ConvertedArg(rawValue, 1)
        }

        return null
    }

    private fun createJsInterfaceProxy(
        binding: JsInterfaceBinding,
        targetType: Class<*>,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker?,
        bridgeClassLoader: ClassLoader?
    ): Any? {
        val callbackInvoker = jsCallbackInvoker ?: return null

        val interfaceClasses = mutableListOf<Class<*>>()

        if (!targetType.isInterface) {
            return null
        }
        requireJavaBridgeInterfaceProxyAllowed(targetType)
        requireJavaBridgeClassAllowed(targetType.name)
        interfaceClasses.add(targetType)

        if (interfaceClasses.isEmpty()) {
            return null
        }

        val deduped = LinkedHashMap<String, Class<*>>()
        interfaceClasses.forEach { cls ->
            deduped[cls.name] = cls
        }
        val proxyInterfaces = deduped.values.toTypedArray()

        val loader =
            bridgeClassLoader
                ?: proxyInterfaces.firstOrNull()?.classLoader
                ?: JsJavaBridgeDelegates::class.java.classLoader

        val proxy =
            Proxy.newProxyInstance(loader, proxyInterfaces) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (method.name) {
                    "toString" ->
                        "JsInterfaceProxy(${binding.jsObjectId}) implements ${proxyInterfaces.joinToString { it.simpleName }}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> null
                }
            }

            invokeJsInterfaceBinding(
                binding = binding,
                method = method,
                args = args ?: emptyArray(),
                objectRegistry = objectRegistry,
                callbackInvoker = callbackInvoker,
                jsCallbackInvoker = jsCallbackInvoker,
                bridgeClassLoader = bridgeClassLoader
            )
        }
        trackJsInterfaceProxy(proxy, callbackInvoker, listOf(binding.jsObjectId))
        return proxy
    }

    private fun createJsInterfaceProxyFromMap(
        rawValue: Map<*, *>,
        targetType: Class<*>,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker?,
        bridgeClassLoader: ClassLoader?
    ): Any? {
        val callbackInvoker = jsCallbackInvoker ?: return null
        if (!targetType.isInterface) {
            return null
        }
        requireJavaBridgeInterfaceProxyAllowed(targetType)
        requireJavaBridgeClassAllowed(targetType.name)
        validateBridgeReturnValue(rawValue)

        val memberMap = LinkedHashMap<String, Any?>()
        rawValue.entries.forEach { entry ->
            val key = entry.key?.toString()?.trim().orEmpty()
            if (key.isNotEmpty()) {
                memberMap[key] = entry.value
            }
        }
        if (memberMap.isEmpty()) {
            return null
        }

        val loader =
            bridgeClassLoader
                ?: targetType.classLoader
                ?: JsJavaBridgeDelegates::class.java.classLoader

        val proxy =
            Proxy.newProxyInstance(loader, arrayOf(targetType)) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (method.name) {
                    "toString" -> "JsInterfaceProxy(map) implements ${targetType.simpleName}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> null
                }
            }

            val runtimeArgs = args ?: emptyArray()
            val resolved = resolveMapInterfaceMember(memberMap, method, runtimeArgs)
            if (!resolved.first) {
                if (isVoidLikeReturnType(method.returnType)) {
                    return@newProxyInstance null
                }
                throw IllegalStateException(
                    "JS interface map does not provide implementation for method ${method.name}"
                )
            }

            val mappedValue = resolved.second
            if (mappedValue is JsInterfaceBinding) {
                return@newProxyInstance invokeJsInterfaceBinding(
                    binding = mappedValue,
                    method = method,
                    args = runtimeArgs,
                    objectRegistry = objectRegistry,
                    callbackInvoker = callbackInvoker,
                    jsCallbackInvoker = jsCallbackInvoker,
                    bridgeClassLoader = bridgeClassLoader
                )
            }

            if (isVoidLikeReturnType(method.returnType)) {
                return@newProxyInstance null
            }

            adaptReturnValue(
                value = mappedValue,
                targetType = method.returnType,
                objectRegistry = objectRegistry,
                jsCallbackInvoker = jsCallbackInvoker,
                bridgeClassLoader = bridgeClassLoader
            )
        }
        val jsObjectIds = linkedSetOf<String>().also { collectJsInterfaceObjectIds(memberMap, it) }
        trackJsInterfaceProxy(proxy, callbackInvoker, jsObjectIds)
        return proxy
    }

    private fun resolveMapInterfaceMember(
        memberMap: MutableMap<String, Any?>,
        method: Method,
        args: Array<out Any?>
    ): Pair<Boolean, Any?> {
        val methodName = method.name
        if (memberMap.containsKey(methodName)) {
            return Pair(true, memberMap[methodName])
        }

        val propertyName = accessorPropertyName(methodName)
        if (propertyName != null) {
            if (methodName.startsWith("set") && args.size == 1) {
                memberMap[propertyName] = args[0]
                return Pair(true, null)
            }
            if (args.isEmpty() && memberMap.containsKey(propertyName)) {
                return Pair(true, memberMap[propertyName])
            }
        }

        return Pair(false, null)
    }

    private fun accessorPropertyName(methodName: String): String? {
        val propertyBase =
            when {
                methodName.startsWith("get") && methodName.length > 3 -> methodName.substring(3)
                methodName.startsWith("is") && methodName.length > 2 -> methodName.substring(2)
                methodName.startsWith("set") && methodName.length > 3 -> methodName.substring(3)
                else -> null
            } ?: return null

        if (propertyBase.isEmpty()) {
            return null
        }

        return propertyBase.replaceFirstChar { ch ->
            if (ch.isUpperCase()) ch.lowercase(Locale.ROOT) else ch.toString()
        }
    }

    private fun isVoidLikeReturnType(returnType: Class<*>): Boolean {
        return returnType == java.lang.Void.TYPE ||
            returnType == Void::class.java ||
            returnType.name == "kotlin.Unit"
    }

    private fun invokeJsInterfaceBinding(
        binding: JsInterfaceBinding,
        method: Method,
        args: Array<out Any?>,
        objectRegistry: ConcurrentHashMap<String, Any>,
        callbackInvoker: JsInterfaceCallbackInvoker,
        jsCallbackInvoker: JsInterfaceCallbackInvoker?,
        bridgeClassLoader: ClassLoader?
    ): Any? {
        val argsJson = serializeCallbackArgs(args, objectRegistry)
        val rawResponse = callbackInvoker.invoke(binding.jsObjectId, method.name, argsJson)
        val response = parseBridgeResponse(rawResponse)

        if (!response.success) {
            if (isVoidLikeReturnType(method.returnType)) {
                AppLogger.e(
                    TAG,
                    "JS interface callback failed for void method ${method.name}: ${response.error}"
                )
                return null
            }
            throw IllegalStateException(
                response.error ?: "JS interface callback failed for method ${method.name}"
            )
        }

        if (isVoidLikeReturnType(method.returnType)) {
            return null
        }

        val decoded = decodeJsonValue(
            raw = response.dataRaw,
            objectRegistry = objectRegistry,
            interpretBridgeMarkers = true
        )
        return adaptReturnValue(
            value = decoded,
            targetType = method.returnType,
            objectRegistry = objectRegistry,
            jsCallbackInvoker = jsCallbackInvoker,
            bridgeClassLoader = bridgeClassLoader
        )
    }

    private fun serializeCallbackArgs(
        args: Array<out Any?>,
        objectRegistry: ConcurrentHashMap<String, Any>
    ): String {
        return withBridgeHandleRegistrationTransaction(objectRegistry) { transaction ->
            val arr = JSONArray()
            args.forEach { arg ->
                validateBridgeReturnValue(arg)
                arr.put(
                    toJsonCompatibleValueUnchecked(
                        value = arg,
                        objectRegistry = objectRegistry,
                        bridgeAware = true,
                        transaction = transaction
                    )
                )
            }
            arr.toString()
        }
    }

    private fun parseBridgeResponse(raw: String): BridgeResponse {
        if (raw.isBlank()) {
            return BridgeResponse(success = false, dataRaw = null, error = "empty bridge response")
        }

        return try {
            validateBridgeInputJsonText(raw)
            val token = JSONTokener(raw).nextValue()
            validateBridgeReturnValue(token)
            if (token is JSONObject) {
                BridgeResponse(
                    success = token.optBoolean("success", false),
                    dataRaw = token.opt("data"),
                    error = token.optString("message").ifBlank { null }
                )
            } else {
                BridgeResponse(
                    success = false,
                    dataRaw = null,
                    error = "invalid bridge response format"
                )
            }
        } catch (e: Exception) {
            BridgeResponse(
                success = false,
                dataRaw = null,
                error = "failed to parse bridge response: ${e.message}"
            )
        }
    }

    private fun adaptReturnValue(
        value: Any?,
        targetType: Class<*>,
        objectRegistry: ConcurrentHashMap<String, Any>,
        jsCallbackInvoker: JsInterfaceCallbackInvoker?,
        bridgeClassLoader: ClassLoader?
    ): Any? {
        if (targetType == java.lang.Void.TYPE || targetType == Void::class.java) {
            return null
        }

        if (value == null) {
            if (targetType.isPrimitive) {
                return defaultPrimitiveValue(targetType)
            }
            return null
        }

        val converted =
            convertArg(
                rawValue = value,
                targetType = targetType,
                objectRegistry = objectRegistry,
                jsCallbackInvoker = jsCallbackInvoker,
                bridgeClassLoader = bridgeClassLoader
            )

        if (converted != null) {
            return converted.value
        }

        if (targetType.isAssignableFrom(value.javaClass)) {
            return value
        }

        throw IllegalArgumentException(
            "cannot convert callback return type ${describeValueType(value)} to ${targetType.name}"
        )
    }

    private fun defaultPrimitiveValue(type: Class<*>): Any {
        return when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> 0
        }
    }

    private fun convertToBoolean(rawValue: Any): ConvertedArg? {
        return when (rawValue) {
            is Boolean -> ConvertedArg(rawValue, 0)
            is Number -> ConvertedArg(rawValue.toInt() != 0, 60)
            is String -> {
                when (rawValue.trim().lowercase(Locale.ROOT)) {
                    "true", "1", "yes", "y" -> ConvertedArg(true, 5)
                    "false", "0", "no", "n" -> ConvertedArg(false, 5)
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun convertToChar(rawValue: Any): ConvertedArg? {
        return when (rawValue) {
            is Char -> ConvertedArg(rawValue, 0)
            is Number -> ConvertedArg(rawValue.toInt().toChar(), 60)
            is String -> {
                val normalized = rawValue.trim()
                if (normalized.length == 1) {
                    ConvertedArg(normalized[0], 5)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun convertToEnum(rawValue: Any, enumType: Class<*>): ConvertedArg? {
        val constants = enumType.enumConstants ?: return null
        return when (rawValue) {
            is String -> {
                constants.firstOrNull { enumConstant ->
                    val enumName = (enumConstant as Enum<*>).name
                    enumName.equals(rawValue.trim(), ignoreCase = true)
                }?.let { ConvertedArg(it, 5) }
            }
            is Number -> {
                val index = rawValue.toInt()
                if (index in constants.indices) {
                    ConvertedArg(constants[index], 6)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun convertToNumber(rawValue: Any, numberType: Class<*>): ConvertedArg? {
        val parsed: Number =
            when (rawValue) {
                is Number -> rawValue
                is String -> rawValue.trim().toDoubleOrNull() ?: return null
                is Boolean -> if (rawValue) 1 else 0
                else -> return null
            }

        return try {
            val converted: Any =
                when (numberType) {
                    java.lang.Byte::class.java -> parsed.toByte()
                    java.lang.Short::class.java -> parsed.toShort()
                    java.lang.Integer::class.java -> parsed.toInt()
                    java.lang.Long::class.java -> parsed.toLong()
                    java.lang.Float::class.java -> parsed.toFloat()
                    java.lang.Double::class.java -> parsed.toDouble()
                    else -> return null
                }
            if (!isLosslessNumericConversion(parsed, numberType, converted)) {
                return null
            }
            val score =
                numericConversionScore(parsed, numberType) +
                    if (rawValue is Number) 0 else 20
            ConvertedArg(converted, score)
        } catch (_: Exception) {
            null
        }
    }

    private fun numericConversionScore(source: Number, targetType: Class<*>): Int {
        val sourceRank = numericTypeRank(source)
        val targetRank = numericTypeRank(targetType)
        if (sourceRank == targetRank) return 0

        val sourceIsIntegral = sourceRank in 0..3
        val targetIsIntegral = targetRank in 0..3
        return when {
            sourceIsIntegral && targetIsIntegral && targetRank > sourceRank ->
                1 + targetRank - sourceRank
            sourceIsIntegral && targetIsIntegral -> 10 + sourceRank - targetRank
            sourceIsIntegral -> 20 + targetRank - 4
            targetIsIntegral -> 30 + targetRank
            targetRank > sourceRank -> 1 + targetRank - sourceRank
            else -> 10 + sourceRank - targetRank
        }
    }

    private fun numericTypeRank(type: Class<*>): Int =
        when (type) {
            java.lang.Byte::class.java -> 0
            java.lang.Short::class.java -> 1
            java.lang.Integer::class.java -> 2
            java.lang.Long::class.java -> 3
            java.lang.Float::class.java -> 4
            java.lang.Double::class.java -> 5
            else -> Int.MAX_VALUE / 2
        }

    private fun numericTypeRank(value: Number): Int =
        when (value) {
            is Byte -> 0
            is Short -> 1
            is Int -> 2
            is Long -> 3
            is Float -> 4
            is Double -> 5
            else -> {
                val decimal = value.toExactBigDecimal()
                if (decimal != null && decimal.stripTrailingZeros().scale() <= 0) {
                    when {
                        decimal >= BigDecimal.valueOf(Int.MIN_VALUE.toLong()) &&
                            decimal <= BigDecimal.valueOf(Int.MAX_VALUE.toLong()) -> 2
                        decimal >= BigDecimal.valueOf(Long.MIN_VALUE) &&
                            decimal <= BigDecimal.valueOf(Long.MAX_VALUE) -> 3
                        else -> 5
                    }
                } else {
                    5
                }
            }
        }

    private fun isLosslessNumericConversion(
        source: Number,
        targetType: Class<*>,
        converted: Any
    ): Boolean {
        return when (targetType) {
            java.lang.Byte::class.java ->
                isExactIntegralValue(source, Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong())
            java.lang.Short::class.java ->
                isExactIntegralValue(source, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
            java.lang.Integer::class.java ->
                isExactIntegralValue(source, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            java.lang.Long::class.java ->
                isExactIntegralValue(source, Long.MIN_VALUE, Long.MAX_VALUE)
            java.lang.Float::class.java ->
                numericValuesEqual(source, converted as Float)
            java.lang.Double::class.java ->
                numericValuesEqual(source, converted as Double)
            else -> false
        }
    }

    private fun isExactIntegralValue(source: Number, minimum: Long, maximum: Long): Boolean {
        val decimal = source.toExactBigDecimal() ?: return false
        if (decimal.stripTrailingZeros().scale() > 0) return false
        return decimal >= BigDecimal.valueOf(minimum) && decimal <= BigDecimal.valueOf(maximum)
    }

    private fun numericValuesEqual(source: Number, converted: Float): Boolean {
        if (source is Float) return source.toRawBits() == converted.toRawBits()
        val sourceDouble = source.toDouble()
        if (sourceDouble.isNaN()) return source is Double && converted.isNaN()
        if (sourceDouble.isInfinite()) {
            return source is Double && converted.toDouble() == sourceDouble
        }
        if (!converted.isFinite()) return false
        return source.toExactBigDecimal()?.compareTo(BigDecimal.valueOf(converted.toDouble())) == 0
    }

    private fun numericValuesEqual(source: Number, converted: Double): Boolean {
        if (source is Double) return source.toRawBits() == converted.toRawBits()
        val sourceDouble = source.toDouble()
        if (sourceDouble.isNaN()) return source is Float && converted.isNaN()
        if (sourceDouble.isInfinite()) {
            return source is Float && converted == sourceDouble
        }
        if (!converted.isFinite()) return false
        return source.toExactBigDecimal()?.compareTo(BigDecimal.valueOf(converted)) == 0
    }

    private fun Number.toExactBigDecimal(): BigDecimal? =
        when (this) {
            is Byte, is Short, is Int, is Long -> BigDecimal.valueOf(toLong())
            is Float -> if (isFinite()) BigDecimal.valueOf(toDouble()) else null
            is Double -> if (isFinite()) BigDecimal.valueOf(this) else null
            else -> toString().toBigDecimalOrNull()
        }

    private fun findField(clazz: Class<*>, fieldName: String, staticOnly: Boolean): Field? {
        return clazz.fields.firstOrNull { field ->
            field.name == fieldName && Modifier.isStatic(field.modifiers) == staticOnly
        }
    }

    private fun hasMethod(clazz: Class<*>, methodName: String, staticOnly: Boolean): Boolean {
        return clazz.methods.any { method ->
            method.name == methodName &&
                Modifier.isStatic(method.modifiers) == staticOnly &&
                isJavaBridgeMethodAllowed(clazz, method)
        }
    }

    private fun findStaticFallbackInstance(clazz: Class<*>): Any? {
        return findNamedStaticInstance(clazz, "Companion")
            ?: findNamedStaticInstance(clazz, "INSTANCE")
    }

    private fun findNamedStaticInstance(clazz: Class<*>, fieldName: String): Any? {
        return runCatching {
            findField(clazz, fieldName, staticOnly = true)?.get(null)
                ?: findGetter(clazz, fieldName, staticOnly = true)?.invoke(null)
        }.getOrNull()
    }

    private fun findGetter(clazz: Class<*>, fieldName: String, staticOnly: Boolean): Method? {
        val normalized = fieldName.trim()
        if (normalized.isEmpty()) {
            return null
        }

        val capitalized =
            normalized.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
            }

        val candidates = listOf("get$capitalized", "is$capitalized")
        return clazz.methods.firstOrNull { method ->
            method.parameterCount == 0 &&
                method.name in candidates &&
                Modifier.isStatic(method.modifiers) == staticOnly
        }
    }

    private fun describeValueType(value: Any?): String {
        return value?.javaClass?.name ?: "null"
    }
}
