package de.jenz.nahtlos.xml

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android's [DocumentBuilderFactory] throws
 * `ParserConfigurationException: This parser does not support specification "Unknown" version "0.0"`
 * for [DocumentBuilderFactory.setFeature] and [DocumentBuilderFactory.setXIncludeAware].
 * The JDK parser accepts the same calls. Each hardening step is therefore best-effort.
 */
object XmlParsers {
    private val features = listOf(
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
    )

    fun documentBuilderFactory(namespaceAware: Boolean): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = namespaceAware
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }
        for ((name, value) in features) {
            runCatching { factory.setFeature(name, value) }
        }
        return factory
    }
}
