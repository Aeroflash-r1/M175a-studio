package com.example.scanner.protocol

/**
 * Handles serialization of [XmlElement]s into valid XML strings.
 * Supports pretty formatting, automatic namespace declaration, and character escaping.
 */
object XmlSerializer {

    /**
     * Serializes an [XmlElement] into an XML string.
     * @param root The root element to serialize.
     * @param includeDeclaration Whether to prepend the XML standard declaration.
     * @param pretty Whether to format the output with indents and line breaks.
     */
    fun serialize(root: XmlElement, includeDeclaration: Boolean = true, pretty: Boolean = true): String {
        val sb = StringBuilder()
        if (includeDeclaration) {
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            if (pretty) sb.append("\n")
        }
        serializeElement(root, sb, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun serializeElement(element: XmlElement, sb: StringBuilder, indent: Int) {
        if (indent >= 0) {
            sb.append("  ".repeat(indent))
        }

        sb.append("<")
        if (element.namespacePrefix != null) {
            sb.append(element.namespacePrefix).append(":")
        }
        sb.append(element.name)

        // Append namespaces declared on this element
        for ((prefix, uri) in element.namespaces) {
            sb.append(" ")
            if (prefix.isEmpty()) {
                sb.append("xmlns=\"").append(escapeXml(uri)).append("\"")
            } else {
                sb.append("xmlns:").append(prefix).append("=\"").append(escapeXml(uri)).append("\"")
            }
        }

        // Append attributes
        for ((name, value) in element.attributes) {
            sb.append(" ").append(name).append("=\"").append(escapeXml(value)).append("\"")
        }

        val hasChildren = element.children.isNotEmpty()
        val hasText = !element.text.isNullOrEmpty()

        if (!hasChildren && !hasText) {
            sb.append(" />")
            if (indent >= 0) sb.append("\n")
            return
        }

        sb.append(">")

        if (hasChildren) {
            if (indent >= 0) sb.append("\n")
            for (child in element.children) {
                serializeElement(child, sb, if (indent >= 0) indent + 1 else -1)
            }
            if (indent >= 0) {
                sb.append("  ".repeat(indent))
            }
        } else if (hasText) {
            sb.append(escapeXml(element.text ?: ""))
        }

        sb.append("</")
        if (element.namespacePrefix != null) {
            sb.append(element.namespacePrefix).append(":")
        }
        sb.append(element.name).append(">")
        if (indent >= 0) {
            sb.append("\n")
        }
    }

    /**
     * Helper to escape special XML characters.
     */
    fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
