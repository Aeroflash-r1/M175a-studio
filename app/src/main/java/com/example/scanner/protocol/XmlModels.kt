package com.example.scanner.protocol

/**
 * Represents an XML Element with attributes, namespaces, text content, and child elements.
 */
data class XmlElement(
    val name: String,
    val namespacePrefix: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val namespaces: Map<String, String> = emptyMap(),
    val text: String? = null,
    val children: List<XmlElement> = emptyList()
) {
    /**
     * Finds a child element by name.
     */
    fun findChild(name: String): XmlElement? {
        return children.firstOrNull { it.name == name || it.localName() == name }
    }

    /**
     * Finds all children by name.
     */
    fun findChildren(name: String): List<XmlElement> {
        return children.filter { it.name == name || it.localName() == name }
    }

    /**
     * Returns the name of the tag without any namespace prefix.
     */
    fun localName(): String {
        return name.substringAfter(':')
    }

    /**
     * Resolves the namespace URI for this element's prefix or current scope.
     */
    fun getNamespaceUri(prefix: String?, parentNamespaces: Map<String, String> = emptyMap()): String? {
        val merged = parentNamespaces + namespaces
        return merged[prefix ?: ""]
    }
}

/**
 * XML parsing or validation exception.
 */
class XmlException(message: String, cause: Throwable? = null) : Exception(message, cause)
