package com.example.scanner.protocol

import java.util.Stack

/**
 * A strict, state-based, and lightweight XML parser in pure Kotlin.
 * Validates matching tags, parses namespaces, extracts attributes, and decodes character entities.
 */
object XmlParser {

    private data class ParsedTag(
        val name: String,
        val prefix: String?,
        val attributes: Map<String, String>,
        val namespaces: Map<String, String>,
        val isClosing: Boolean,
        val isSelfClosing: Boolean
    )

    /**
     * Parses an XML string into an [XmlElement] tree.
     * Throws [XmlException] on malformed XML or tag mismatch.
     */
    fun parse(xml: String): XmlElement {
        val sanitized = sanitize(xml)
        val tokens = tokenize(sanitized)
        
        val stack = Stack<MutableElement>()
        var root: XmlElement? = null

        for (token in tokens) {
            when (token) {
                is Token.Tag -> {
                    val tag = parseTag(token.content)
                    if (tag.isClosing) {
                        if (stack.isEmpty()) {
                            throw XmlException("Unexpected closing tag: </${tag.name}>")
                        }
                        val top = stack.pop()
                        val expected = if (tag.prefix != null) "${tag.prefix}:${tag.name}" else tag.name
                        val actual = if (top.prefix != null) "${top.prefix}:${top.name}" else top.name
                        if (expected != actual) {
                            throw XmlException("Tag mismatch: Expected </$actual>, but got </$expected>")
                        }
                        val finalized = top.toXmlElement()
                        if (stack.isEmpty()) {
                            root = finalized
                        } else {
                            stack.peek().children.add(finalized)
                        }
                    } else if (tag.isSelfClosing) {
                        val elem = XmlElement(
                            name = tag.name,
                            namespacePrefix = tag.prefix,
                            attributes = tag.attributes,
                            namespaces = tag.namespaces,
                            text = null,
                            children = emptyList()
                        )
                        if (stack.isEmpty()) {
                            if (root != null) throw XmlException("Multiple root elements found")
                            root = elem
                        } else {
                            stack.peek().children.add(elem)
                        }
                    } else {
                        val newElem = MutableElement(
                            name = tag.name,
                            prefix = tag.prefix,
                            attributes = tag.attributes.toMutableMap(),
                            namespaces = tag.namespaces.toMutableMap()
                        )
                        stack.push(newElem)
                    }
                }
                is Token.Text -> {
                    if (stack.isNotEmpty()) {
                        val trimmed = unescapeXml(token.content.trim())
                        if (trimmed.isNotEmpty()) {
                            stack.peek().text = (stack.peek().text ?: "") + trimmed
                        }
                    }
                }
            }
        }

        if (stack.isNotEmpty()) {
            val unclosed = stack.peek()
            val name = if (unclosed.prefix != null) "${unclosed.prefix}:${unclosed.name}" else unclosed.name
            throw XmlException("Unclosed tag detected: <$name>")
        }

        return root ?: throw XmlException("No root element found in XML")
    }

    private fun sanitize(xml: String): String {
        var result = xml.trim()
        // Strip XML declarations
        if (result.startsWith("<?xml")) {
            val endIdx = result.indexOf("?>")
            if (endIdx != -1) {
                result = result.substring(endIdx + 2).trim()
            }
        }
        // Strip Comments <!-- ... -->
        while (true) {
            val startIdx = result.indexOf("<!--")
            if (startIdx == -1) break
            val endIdx = result.indexOf("-->", startIdx)
            if (endIdx == -1) throw XmlException("Unclosed XML comment detected")
            result = result.removeRange(startIdx, endIdx + 3)
        }
        return result
    }

    private sealed interface Token {
        data class Tag(val content: String) : Token
        data class Text(val content: String) : Token
    }

    private fun tokenize(xml: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val len = xml.length

        while (i < len) {
            val startBracket = xml.indexOf('<', i)
            if (startBracket == -1) {
                val text = xml.substring(i)
                if (text.trim().isNotEmpty()) {
                    tokens.add(Token.Text(text))
                }
                break
            }

            if (startBracket > i) {
                val text = xml.substring(i, startBracket)
                if (text.trim().isNotEmpty()) {
                    tokens.add(Token.Text(text))
                }
            }

            val endBracket = xml.indexOf('>', startBracket)
            if (endBracket == -1) {
                throw XmlException("Unclosed tag bracket starting at index $startBracket")
            }

            val tagContent = xml.substring(startBracket + 1, endBracket)
            tokens.add(Token.Tag(tagContent))
            i = endBracket + 1
        }
        return tokens
    }

    private fun parseTag(content: String): ParsedTag {
        var str = content.trim()
        val isClosing = str.startsWith("/")
        if (isClosing) {
            str = str.substring(1).trim()
        }
        val isSelfClosing = str.endsWith("/")
        if (isSelfClosing) {
            str = str.substring(0, str.length - 1).trim()
        }

        // Extract tag name (could have namespace prefix)
        val nameParts = splitTagName(str)
        val fullName = nameParts.first
        val rawAttributes = nameParts.second

        val colonIdx = fullName.indexOf(':')
        val prefix = if (colonIdx != -1) fullName.substring(0, colonIdx) else null
        val localName = if (colonIdx != -1) fullName.substring(colonIdx + 1) else fullName

        val attributes = mutableMapOf<String, String>()
        val namespaces = mutableMapOf<String, String>()

        parseAttributes(rawAttributes, attributes, namespaces)

        return ParsedTag(
            name = localName,
            prefix = prefix,
            attributes = attributes,
            namespaces = namespaces,
            isClosing = isClosing,
            isSelfClosing = isSelfClosing
        )
    }

    private fun splitTagName(str: String): Pair<String, String> {
        var i = 0
        val len = str.length
        while (i < len && !str[i].isWhitespace()) {
            i++
        }
        val name = str.substring(0, i)
        val rest = if (i < len) str.substring(i).trim() else ""
        return Pair(name, rest)
    }

    private fun parseAttributes(
        str: String,
        attributes: MutableMap<String, String>,
        namespaces: MutableMap<String, String>
    ) {
        var i = 0
        val len = str.length

        while (i < len) {
            // Skip whitespace
            while (i < len && str[i].isWhitespace()) i++
            if (i >= len) break

            // Read attribute/namespace name
            val nameStart = i
            while (i < len && str[i] != '=' && !str[i].isWhitespace()) {
                i++
            }
            val attrName = str.substring(nameStart, i)

            // Find '='
            while (i < len && str[i].isWhitespace()) i++
            if (i >= len || str[i] != '=') {
                throw XmlException("Malformed attribute: expected '=' after $attrName")
            }
            i++ // skip '='

            // Find value quote
            while (i < len && str[i].isWhitespace()) i++
            if (i >= len || (str[i] != '"' && str[i] != '\'')) {
                throw XmlException("Malformed attribute: expected quote for $attrName")
            }
            val quote = str[i]
            i++ // skip quote

            val valStart = i
            while (i < len && str[i] != quote) {
                i++
            }
            if (i >= len) {
                throw XmlException("Unclosed attribute value for $attrName")
            }
            val attrVal = str.substring(valStart, i)
            i++ // skip ending quote

            if (attrName.startsWith("xmlns")) {
                val nsPrefix = if (attrName.startsWith("xmlns:")) attrName.substring(6) else ""
                namespaces[nsPrefix] = attrVal
            } else {
                attributes[attrName] = attrVal
            }
        }
    }

    private fun unescapeXml(str: String): String {
        return str.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private class MutableElement(
        val name: String,
        val prefix: String?,
        val attributes: MutableMap<String, String>,
        val namespaces: MutableMap<String, String>,
        var text: String? = null,
        val children: MutableList<XmlElement> = mutableListOf()
    ) {
        fun toXmlElement(): XmlElement = XmlElement(
            name = name,
            namespacePrefix = prefix,
            attributes = attributes,
            namespaces = namespaces,
            text = text,
            children = children
        )
    }
}
