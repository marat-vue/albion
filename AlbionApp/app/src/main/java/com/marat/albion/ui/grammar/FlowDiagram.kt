package com.marat.albion.ui.grammar

import com.marat.albion.ui.components.AlbionIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marat.albion.data.grammar.FlowGraph
import com.marat.albion.data.grammar.FlowNode
import com.marat.albion.data.grammar.NodeShape
import com.marat.albion.ui.components.Eyebrow

/**
 * Нативная отрисовка схем Mermaid для узкого экрана: граф раскладывается в
 * вертикальное дерево. Цепочки идут сверху вниз, развилки — отступом с подписью
 * ветки («Да», «Нет»), повторные узлы — ссылкой. Вопросы — в инвертированной плашке.
 */
private class TreeNode(val node: FlowNode, val reference: Boolean, val branches: List<Branch>)
private class Branch(val label: String?, val target: TreeNode)

private fun buildForest(graph: FlowGraph): List<TreeNode> {
    val visited = HashSet<String>()
    fun build(id: String): TreeNode {
        val node = graph.node(id)
        if (!visited.add(id)) return TreeNode(node, reference = true, branches = emptyList())
        return TreeNode(node, reference = false, branches = graph.outgoing(id).map { e -> Branch(e.label, build(e.to)) })
    }
    val roots = graph.nodes.filter { graph.incoming(it.id).isEmpty() }.ifEmpty { listOf(graph.nodes.first()) }
    val forest = roots.map { build(it.id) }.toMutableList()
    graph.nodes.filter { it.id !in visited }.forEach { forest += build(it.id) }
    return forest
}

@Composable
fun FlowDiagram(graph: FlowGraph, modifier: Modifier = Modifier) {
    val forest = remember(graph) { buildForest(graph) }
    Column(modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(16.dp)) {
        Eyebrow("Схема")
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { forest.forEach { TreeView(it) } }
    }
}

@Composable
private fun TreeView(t: TreeNode, depth: Int = 0) {
    Column {
        NodeBox(t.node, t.reference)
        if (t.reference) return@Column
        val single = t.branches.singleOrNull()
        if (single != null && single.label == null) {
            Arrow()
            TreeView(single.target, depth + 1)
        } else if (t.branches.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                t.branches.forEach { b ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Spacer(Modifier.width(if (depth < 2) 12.dp else 0.dp))
                        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                        Spacer(Modifier.width(if (depth < 2) 12.dp else 0.dp))
                        Column(Modifier.weight(1f)) {
                            if (b.label != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(b.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Icon(AlbionIcons.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                }
                            } else Arrow()
                            TreeView(b.target, depth + 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Arrow() {
    Box(Modifier.padding(start = 10.dp).height(22.dp), contentAlignment = Alignment.Center) {
        Icon(AlbionIcons.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NodeBox(node: FlowNode, reference: Boolean) {
    val decision = node.shape == NodeShape.Decision && !reference
    val shape = when (node.shape) {
        NodeShape.Round, NodeShape.Circle -> com.marat.albion.ui.theme.Capsule
        else -> MaterialTheme.shapes.small
    }
    val scale = LocalMdEnv.current.scale
    Row(
        Modifier.widthIn(min = 80.dp)
            .background(if (decision) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, if (reference) MaterialTheme.colorScheme.outlineVariant else if (decision) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (reference) {
            Icon(AlbionIcons.Redo, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
        }
        InlineText(
            node.label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp * scale, lineHeight = 20.sp * scale, color = when {
                    decision -> MaterialTheme.colorScheme.background
                    reference -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }),
        )
    }
}
