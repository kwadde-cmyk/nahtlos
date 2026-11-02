package de.jenz.nahtlos.opml

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class OpmlOutline(
    val title: String,
    val feedUrl: String,
)

object Opml {
    fun parse(xml: String): List<OpmlOutline> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val source = InputSource(StringReader(xml.removePrefix("\uFEFF")))
        val document = factory.newDocumentBuilder().parse(source)
        val found = mutableListOf<OpmlOutline>()
        walk(document.documentElement, found)
        return found.distinctBy { it.feedUrl.trim() }
    }

    fun write(title: String, outlines: List<OpmlOutline>): String {
        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().newDocument()
        val opml = document.createElement("opml").apply { setAttribute("version", "2.0") }
        document.appendChild(opml)
        val head = document.createElement("head")
        val titleNode = document.createElement("title")
        titleNode.textContent = title
        head.appendChild(titleNode)
        opml.appendChild(head)
        val body = document.createElement("body")
        outlines.forEach { outline ->
            val node = document.createElement("outline")
            node.setAttribute("text", outline.title.ifBlank { outline.feedUrl })
            node.setAttribute("title", outline.title.ifBlank { outline.feedUrl })
            node.setAttribute("type", "rss")
            node.setAttribute("xmlUrl", outline.feedUrl)
            body.appendChild(node)
        }
        opml.appendChild(body)
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }

    private fun walk(node: Node?, into: MutableList<OpmlOutline>) {
        if (node == null) return
        if (node is Element && node.tagName.equals("outline", ignoreCase = true)) {
            val url = node.attr("xmlUrl") ?: node.attr("xmlurl")
            if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                val title = node.attr("title") ?: node.attr("text") ?: url
                into += OpmlOutline(title = title, feedUrl = url.trim())
            }
        }
        val children = node.childNodes
        for (i in 0 until children.length) walk(children.item(i), into)
    }

    private fun Element.attr(name: String): String? {
        if (!hasAttribute(name)) return null
        return getAttribute(name).trim().ifEmpty { null }
    }
}
