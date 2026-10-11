package com.ai.assistance.operit.ui.common.dynamicui

/** Public authoring contract; shared by the compiler and the on-demand model guide.
 * Named arguments avoid depending on the ordering of AndroidX property lists.
 */
internal object CardAuthoringCatalog {
    data class Field(val name: String, val type: String, val required: Boolean)
    private fun fields(value: String) = value.split(' ').filter(String::isNotBlank).map {
        val (key, type) = it.split(':')
        Field(key.removeSuffix("?"), type, !key.endsWith("?"))
    }
    val components = linkedMapOf(
        "Text" to "text:string variant?:string textAlign?:string lineClamp?:number",
        "Icon" to "name:string",
        "Row" to "children:children gap?:string align?:string justify?:string wrap?:boolean",
        "Column" to "children:children gap?:string align?:string",
        "Panel" to "child:child padding?:string radius?:string background?:string border?:boolean maxWidth?:number",
        "Scroll" to "child:child maxHeight:number",
        "Popover" to "label:string child:child",
        "Vector" to "label:string width:number height:number shapes:array",
        "List" to "children:children", "Card" to "child:child", "Divider" to "",
        "Button" to "child:child action:action variant?:string checks?:array",
        "CheckBox" to "label:string value:boolean",
        "Heading" to "text:string", "Display" to "text:string label?:string align?:string",
        "Header" to "text:string label?:string status?:string tone?:string",
        "InfoRow" to "label:string text:string tone?:string",
        "Badge" to "text:string tone?:string", "Code" to "text:string",
        "Progress" to "number:number label?:string",
        "Table" to "columns:array rows:array",
        "Grid" to "children:children columnCount?:number columnSpans?:array minItemWidth?:number",
        "Form" to "label:string children:children",
        "Expandable" to "label:string children:children expanded:boolean",
        "TextField" to "label:string value:string inputType?:string",
        "TextArea" to "label:string value:string inputType?:string",
        "Switch" to "label:string checked:boolean",
        "RadioGroup" to "label:string options:array value:string",
        "Select" to "label:string options:array value:string",
        "ChoicePicker" to "label:string options:array value:string",
        "Slider" to "label:string min:number max:number number:number steps?:number",
        "DatePicker" to "label:string value:string",
        "Segmented" to "label:string options:array value:string",
        "Tabs" to "label:string labels:array children:children value:string",
        "Carousel" to "label:string children:children value:string",
        "Conditional" to "visible:boolean children:children",
        "RichText" to "runs:array",
        "Chart" to "label:string type:string series:array labels?:array",
        "Image" to "label:string url:string", "Audio" to "label:string url:string",
        "Video" to "label:string url:string",
        "Link" to "label:string text:string url:string", "File" to "label:string text:string url:string",
        "Product" to "label:string text:string price:string url:string imageUrl?:string",
        "Place" to "label:string text:string address:string url:string imageUrl?:string",
        "Map" to "label:string markers:array route:boolean",
        "Draft" to "label:string value:string",
    ).mapValues { (_, value) -> fields(value) }

    val staticFields = setOf("variant", "min", "max", "steps", "columnCount", "columnSpans", "minItemWidth",
        "type", "align", "gap", "justify", "wrap", "padding", "radius", "background", "border", "maxWidth",
        "maxHeight", "textAlign", "lineClamp", "width", "height", "shapes")
    val enums = mapOf(
        "Text.variant" to setOf("h1", "h2", "h3", "h4", "h5", "body", "caption"),
        "Button.variant" to setOf("primary", "default", "borderless"),
        "Chart.type" to setOf("line", "bar", "pie", "scatter"),
        "TextField.inputType" to setOf("text", "number"),
        "TextArea.inputType" to setOf("text", "number"),
        "Display.align" to setOf("start", "end"),
        "Header.tone" to setOf("neutral", "success", "info", "warning", "error"),
        "InfoRow.tone" to setOf("neutral", "success", "info", "warning", "error"),
        "Badge.tone" to setOf("neutral", "success", "info", "warning", "error"),
        "Text.textAlign" to setOf("start", "center", "end"),
        "Row.gap" to setOf("none", "xs", "sm", "md", "lg"),
        "Column.gap" to setOf("none", "xs", "sm", "md", "lg"),
        "Row.align" to setOf("start", "center", "end"),
        "Column.align" to setOf("start", "center", "end"),
        "Row.justify" to setOf("start", "center", "end", "between"),
        "Panel.padding" to setOf("none", "xs", "sm", "md", "lg"),
        "Panel.radius" to setOf("none", "sm", "md", "lg"),
        "Panel.background" to setOf("transparent", "surface", "inset", "accentMuted"),
    )
    val ranges = mapOf(
        "Text.lineClamp" to 1..8, "Panel.maxWidth" to 120..960, "Scroll.maxHeight" to 80..480,
        "Grid.minItemWidth" to 80..320, "Vector.width" to 1..1000, "Vector.height" to 1..1000,
    )
    fun isStatic(component: String, field: String) =
        field in staticFields || component == "Table" && field == "columns" || component == "Vector" && field == "label"

    fun guide(): String = buildString {
        appendLine("Use named arguments for every component and function. Inline child components or reference named declarations.")
        appendLine("Object entries use key:value; component/function arguments use key=value. A single unambiguous punctuation mistake may be repaired locally and reported as repaired=true; values, functions and validation limits are never rewritten.")
        appendLine("Local calculations allow at most 128 calls across the complete card document. There is no separate 6-level function nesting limit; overall source and JSON structure limits still apply.")
        appendLine("List lays out its static children vertically at natural height and scrolls with the chat. For a separately scrolling area, wrap it in Scroll(child=..., maxHeight=80..480).")
        components.forEach { (name, fields) ->
            appendLine("$name(${fields.joinToString {
                "${it.name}${if (it.required) "" else "?"}: ${enums["$name.${it.name}"]?.joinToString("|") ?: ranges["$name.${it.name}"]?.let { r -> "${r.first}..${r.last}" } ?: it.type}${if (isStatic(name, it.name)) " [literal]" else ""}"
            }})")
        }
        appendLine("Pure local functions:")
        CardFunctions.specs.forEach { (name, spec) ->
            appendLine("$name(${(spec.required.map { "$it: value" } + spec.optional.map { "$it?: value" }).joinToString()})")
        }
        appendLine("Event(name: string, context?: object) creates a Button action. It does not execute while compiling.")
    }
}
