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
        "Text" to "text:string variant?:string",
        "Icon" to "name:string",
        "Row" to "children:children", "Column" to "children:children",
        "List" to "children:children", "Card" to "child:child", "Divider" to "",
        "Button" to "child:child action:action variant?:string checks?:array",
        "CheckBox" to "label:string value:boolean",
        "Heading" to "text:string", "Display" to "text:string label?:string align?:string",
        "Header" to "text:string label?:string status?:string tone?:string",
        "InfoRow" to "label:string text:string tone?:string",
        "Badge" to "text:string tone?:string", "Code" to "text:string",
        "Progress" to "number:number label?:string",
        "Table" to "columns:array rows:array",
        "Grid" to "children:children columnCount?:number columnSpans?:array",
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

    val staticFields = setOf("variant", "min", "max", "steps", "columnCount", "columnSpans", "type", "align")
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
    )
    fun isStatic(component: String, field: String) =
        field in staticFields || component == "Table" && field in setOf("rows", "columns")

    fun guide(): String = buildString {
        appendLine("Use named arguments for every component and function. Inline child components or reference named declarations.")
        components.forEach { (name, fields) ->
            appendLine("$name(${fields.joinToString {
                "${it.name}${if (it.required) "" else "?"}: ${enums["$name.${it.name}"]?.joinToString("|") ?: it.type}${if (isStatic(name, it.name)) " [literal]" else ""}"
            }})")
        }
        appendLine("Pure local functions:")
        CardFunctions.specs.forEach { (name, spec) ->
            appendLine("$name(${(spec.required.map { "$it: value" } + spec.optional.map { "$it?: value" }).joinToString()})")
        }
        appendLine("Event(name: string, context?: object) creates a Button action. It does not execute while compiling.")
    }
}
