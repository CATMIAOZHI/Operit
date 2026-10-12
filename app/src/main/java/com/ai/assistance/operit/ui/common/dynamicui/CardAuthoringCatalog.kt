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
    val changeComponents = setOf("CheckBox", "TextField", "TextArea", "Switch", "RadioGroup",
        "Select", "ChoicePicker", "Slider", "DatePicker", "Segmented", "Tabs", "Carousel", "Expandable", "Stepper")
    val components = linkedMapOf(
        "Text" to "text:string variant?:string textAlign?:string lineClamp?:number",
        "Icon" to "name:string",
        "Row" to "children:children gap?:string align?:string justify?:string wrap?:boolean",
        "Column" to "children:children gap?:string align?:string",
        "Panel" to "child:child padding?:string radius?:string background?:string border?:boolean maxWidth?:number",
        "Scroll" to "child:child maxHeight:number",
        "Frame" to "child:child minWidth?:number maxWidth?:number minHeight?:number maxHeight?:number aspectRatio?:number",
        "Popover" to "label:string child:child",
        "Tooltip" to "label:string text:string",
        "Stepper" to "label:string labels:array current:number",
        "Formula" to "label:string text:string",
        "Vector" to "label:string width:number height:number shapes:array",
        "List" to "children:children", "Card" to "child:child", "Divider" to "",
        "Repeat" to "items:array key:string child:child maxItems?:number",
        "Button" to "child:child action:action variant?:string checks?:array loading?:boolean",
        "Pressable" to "child:child label:string action:action disabled?:boolean selected?:boolean",
        "CheckBox" to "label:string value:boolean",
        "Heading" to "text:string", "Display" to "text:string label?:string align?:string",
        "Header" to "text:string label?:string status?:string tone?:string",
        "InfoRow" to "label:string text:string tone?:string",
        "Badge" to "text:string tone?:string", "Code" to "text:string",
        "Progress" to "number:number label?:string",
        "Table" to "columns:array rows:array columnWidths?:array maxHeight?:number",
        "Grid" to "children:children columnCount?:number columnSpans?:array minItemWidth?:number",
        "Form" to "label:string children:children",
        "Expandable" to "label:string children:children expanded:boolean",
        "TextField" to "label:string value:string inputType?:string error?:string mask?:string",
        "TextArea" to "label:string value:string inputType?:string error?:string minLines?:number maxLines?:number",
        "Switch" to "label:string checked:boolean",
        "RadioGroup" to "label:string options:array value:string",
        "Select" to "label:string options:array value:string",
        "ChoicePicker" to "label:string options:array value:string",
        "Slider" to "label:string min:number max:number number:number steps?:number",
        "DatePicker" to "label:string value:string clearable?:boolean minDate?:string maxDate?:string",
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
    ).mapValues { (name, value) -> fields(value) + if (name in changeComponents)
        fields("onChange?:action disabled?:boolean") else emptyList() }

    val staticFields = setOf("variant", "min", "max", "steps", "columnCount", "columnSpans", "minItemWidth",
        "type", "align", "gap", "justify", "wrap", "padding", "radius", "background", "border", "maxWidth",
        "maxHeight", "minWidth", "minHeight", "aspectRatio", "textAlign", "lineClamp", "width", "height", "key", "maxItems",
        "columnWidths", "minLines", "maxLines", "mask")
    val enums = mapOf(
        "Text.variant" to setOf("h1", "h2", "h3", "h4", "h5", "body", "caption"),
        "Button.variant" to setOf("primary", "default", "borderless"),
        "Chart.type" to setOf("line", "bar", "pie", "scatter"),
        "TextField.inputType" to setOf("text", "number"),
        "TextArea.inputType" to setOf("text", "number"),
        "TextField.mask" to setOf("none", "password", "digits"),
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
        "Repeat.maxItems" to 1..32,
        "Table.maxHeight" to 80..480, "TextArea.minLines" to 1..12, "TextArea.maxLines" to 1..12,
        "Frame.minWidth" to 0..960, "Frame.maxWidth" to 1..960,
        "Frame.minHeight" to 0..480, "Frame.maxHeight" to 1..480,
    )
    fun isStatic(component: String, field: String) =
        field in staticFields || component == "Table" && field == "columns"

    fun guide(): String = buildString {
        appendLine("Use named arguments for every component and function. Inline child components or reference named declarations.")
        appendLine("Object entries use key:value; component/function arguments use key=value. A single unambiguous punctuation mistake may be repaired locally and reported as repaired=true; values, functions and validation limits are never rewritten.")
        appendLine("Local calculations allow at most 128 calls across the complete card document. There is no separate 6-level function nesting limit; overall source and JSON structure limits still apply.")
        appendLine("List lays out its static children vertically at natural height and scrolls with the chat. For a separately scrolling area, wrap it in Scroll(child=..., maxHeight=80..480).")
        appendLine("Repeat(items=arrayBindingOrCalculation,key=\"id\",child=template,maxItems=20) renders object items with stable unique nonempty string keys. Maximum 32 rows per list and 512 rendered nodes across all nested lists. Nested Repeat uses items=item(field=\"children\"); item/item_state refer to the nearest row. Use item_state(field=\"done\",default=false) for independent scalar row state. Defaults must agree; state follows Repeat ID and all ancestor keys across filters, reorder and restore, including hidden rows within the 32K quota. Absolute bindings remain shared. Empty arrays render no rows.")
        appendLine("Pressable(label,child,action,selected?,disabled?) makes display-only content clickable; nested interactive descendants are rejected. Completed nodes should remain clickable. Input onChange supports only local_action. event_value(field=\"value\"|\"previous\"|\"index\") is restricted to its context; index is -1 when not indexed. Initialization, restore and programmatic updates do not fire changes.")
        appendLine("Vector shape arrays, types and point structures are literal; coordinates, tone, stroke, filled and text can bind to state. arrow(x1,y1,x2,y2) draws a terminal arrowhead. Runtime invalid geometry displays a recoverable local error. row_set uses {op:\"row_set\",repeat:\"listId\",key:\"itemKey\",field:\"done\",value:true,parents:[\"outerKey\"]}; parents is optional for top-level lists and lists every ancestor key from outermost inward. Only declared fields of currently present rows can change.")
        appendLine("DatePicker minDate/maxDate are ISO dates in 1900..2100, clearable writes empty string. Bounds do not rewrite existing data. TextField mask=none|password|digits; password masks only display, digits accepts ASCII digits without reformatting. TextArea minLines/maxLines default3/8. error is a displayed string, not automatic validation. Button loading disables actions while preserving its label.")
        appendLine("Table columnWidths is one literal64..480dp width per column; maxHeight=80..480 bounds the scrolling body below a fixed header. RichText runs accept strike/highlight/tabular booleans. Tooltip opens on tap/long-press and closes like Popover. Stepper current is a bound zero-based integer; previous steps remain selectable, onChange stays local.")
        appendLine("Formula text supports a bounded TeX subset: 1024 chars, 64 commands, 12 brace levels; frac/sqrt/sum/prod/int/lim/infty/left/right, common Greek, arithmetic relations, sin/cos/tan/log/ln/exp/text/mathrm/mathbf/overline/vec/hat. No macros, environments, files or size commands; invalid dynamic formulas show a local error.")
        appendLine("arraySort uses same-type scalar values, optional field and direction asc/desc, stable order for ties. arrayFind returns the first equal item or explicit fallback. arrayUpdateByKey requires unique scalar keys, cannot patch the key, returns a new array and leaves unmatched data unchanged. None writes state directly.")
        appendLine("Frame applies bounded size constraints to one child; available screen constraints always win. aspectRatio is width/height from 0.25 to 4. Avoid fixed height for text/forms with large fonts. Vector also supports quadratic(x1,y1,cx,cy,x2,y2) and cubic(x1,y1,cx1,cy1,cx2,cy2,x2,y2) shape objects; all coordinates stay in the viewport.")
        components.forEach { (name, fields) ->
            appendLine("$name(${fields.joinToString {
                "${it.name}${if (it.required) "" else "?"}: ${enums["$name.${it.name}"]?.joinToString("|") ?: ranges["$name.${it.name}"]?.let { r -> "${r.first}..${r.last}" } ?: it.type}${if (isStatic(name, it.name)) " [literal]" else ""}"
            }})")
        }
        appendLine("Pure local functions:")
        CardFunctions.specs.forEach { (name, spec) ->
            appendLine("$name(${(spec.required.map { "$it: value" } + spec.optional.map { "$it?: value" }).joinToString()})")
        }
        appendLine("Event(name: string, context?: object) describes a Button/Pressable action or a local-only onChange. It does not execute while compiling.")
    }
}
