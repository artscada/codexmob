package com.codex.android.bridge.gui

import android.util.Log
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object XmlLayoutParser {
    private const val TAG = "XmlLayoutParser"

    data class NodeBounds(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
        val centerX: Int get() = (x1 + x2) / 2
        val centerY: Int get() = (y1 + y2) / 2
    }

    fun findElementCoordinates(xmlFile: File, selector: String, selectorType: String): Pair<Int, Int>? {
        if (!xmlFile.exists()) {
            Log.e(TAG, "XML file does not exist: ${xmlFile.absolutePath}")
            return null
        }

        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xmlFile)
            val root = doc.documentElement
            
            val matchingNode = findNodeRecursive(root, selector, selectorType.lowercase())
            if (matchingNode != null) {
                val boundsStr = matchingNode.getAttribute("bounds")
                val bounds = parseBounds(boundsStr)
                if (bounds != null) {
                    return Pair(bounds.centerX, bounds.centerY)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing layout XML", e)
        }
        return null
    }

    private fun findNodeRecursive(element: Element, selector: String, selectorType: String): Element? {
        val attrValue = when (selectorType) {
            "text" -> element.getAttribute("text")
            "id", "resource-id" -> element.getAttribute("resource-id")
            "desc", "content-desc" -> element.getAttribute("content-desc")
            "class", "class-name" -> element.getAttribute("class")
            else -> ""
        }

        if (attrValue.isNotBlank()) {
            if (selectorType == "text" && attrValue.equals(selector, ignoreCase = true)) {
                return element
            }
            if (selectorType == "text" && attrValue.contains(selector, ignoreCase = true)) {
                return element
            }
            if ((selectorType == "id" || selectorType == "resource-id") && attrValue.endsWith(selector)) {
                return element
            }
            if ((selectorType == "desc" || selectorType == "content-desc") && attrValue.contains(selector, ignoreCase = true)) {
                return element
            }
            if (selectorType == "class" && attrValue.endsWith(selector)) {
                return element
            }
        }

        if (selectorType == "text_contains" && element.getAttribute("text").contains(selector, ignoreCase = true)) {
            return element
        }

        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val found = findNodeRecursive(node as Element, selector, selectorType)
                if (found != null) return found
            }
        }
        return null
    }

    fun parseBounds(boundsStr: String): NodeBounds? {
        try {
            val regex = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
            val match = regex.find(boundsStr) ?: return null
            val (x1, y1, x2, y2) = match.destructured
            return NodeBounds(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing bounds string: $boundsStr", e)
            return null
        }
    }
}
