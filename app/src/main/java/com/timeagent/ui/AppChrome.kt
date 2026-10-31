package com.timeagent.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class NavigationTab(
    val name: String,
    val icon: ImageVector,
)

private val NavigationTabs = listOf(
    NavigationTab("今天", Icons.Rounded.CalendarViewWeek),
    NavigationTab("计划", Icons.Rounded.Checklist),
    NavigationTab("复盘", Icons.Rounded.Insights),
    NavigationTab("管家", Icons.Rounded.PersonOutline),
)

/**
 * Four destinations around an injected voice slot. The bar features a refined hairline border,
 * smooth animated tab pills, and a balanced well sized for an eye-catching yet restrained voice action.
 */
@Composable
fun TimeAgentNavigation(
    selected: Int,
    onSelect: (Int) -> Unit,
    voice: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(
            thickness = 0.8.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavigationTabItem(NavigationTabs[0], selected == 0) { onSelect(0) }
            NavigationTabItem(NavigationTabs[1], selected == 1) { onSelect(1) }
            Box(
                Modifier
                    .width(72.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                voice()
            }
            NavigationTabItem(NavigationTabs[2], selected == 2) { onSelect(2) }
            NavigationTabItem(NavigationTabs[3], selected == 3) { onSelect(3) }
        }
    }
}

@Composable
private fun RowScope.NavigationTabItem(
    tab: NavigationTab,
    selected: Boolean,
    onClick: () -> Unit,
)
{
    val colors = MaterialTheme.colorScheme
    val iconTint by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.85f),
        label = "navigationIcon",
    )
    val labelTint by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.80f),
        label = "navigationLabel",
    )
    val pillColor by animateColorAsState(
        targetValue = if (selected) colors.primaryContainer.copy(alpha = 0.75f) else Color.Transparent,
        label = "navigationPill",
    )

    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { contentDescription = "${tab.name}页面" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .width(46.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(pillColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(if (selected) 21.dp else 20.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.name,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = labelTint,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
