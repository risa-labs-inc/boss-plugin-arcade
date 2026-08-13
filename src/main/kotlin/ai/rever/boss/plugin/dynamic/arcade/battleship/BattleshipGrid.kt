package ai.rever.boss.plugin.dynamic.arcade.battleship

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.boss.plugin.dynamic.arcade.plainClickable

/** How one cell should read. Kept dumb so both boards can share the grid. */
enum class CellMark { EMPTY, SHIP, MISS, HIT, SUNK, PREVIEW, BLOCKED }

private fun fillFor(mark: CellMark): Color = when (mark) {
    CellMark.EMPTY -> ArcadeColors.Cell
    CellMark.SHIP -> ArcadeColors.InkSoft
    CellMark.MISS -> ArcadeColors.Cell
    CellMark.HIT -> ArcadeColors.Pink
    CellMark.SUNK -> ArcadeColors.PinkDeep
    CellMark.PREVIEW -> ArcadeColors.Pink.copy(alpha = 0.35f)
    CellMark.BLOCKED -> Color(0xFFE0B4B4)
}

/**
 * A 10x10 board. [markAt] decides each cell's look; [onCellClick] is null for a
 * read-only board (your own fleet during play).
 *
 * The grid keeps a 1:1 aspect ratio and caps its width so a wide split-view tab
 * does not stretch it into a rectangle.
 */
@Composable
fun BattleshipGrid(
    markAt: (Int) -> CellMark,
    modifier: Modifier = Modifier,
    onCellClick: ((Int) -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .widthIn(max = 380.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(ArcadeColors.Frame)
            .padding(6.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (row in 0 until BattleshipLogic.SIZE) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (col in 0 until BattleshipLogic.SIZE) {
                        val cell = BattleshipLogic.cellOf(row, col)
                        val mark = markAt(cell)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(4.dp))
                                .background(fillFor(mark))
                                .let { m ->
                                    if (onCellClick != null) m.plainClickable { onCellClick(cell) } else m
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (mark) {
                                // A miss is a dot; a hit is a cross. Colour alone
                                // would not survive a colour-blind player.
                                CellMark.MISS -> Box(
                                    Modifier.size(6.dp).clip(CircleShape)
                                        .background(ArcadeColors.Muted),
                                )
                                CellMark.HIT, CellMark.SUNK -> Text(
                                    "✕",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BattleshipBoardLabel(text: String, hint: String? = null) {
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Text(text, color = ArcadeColors.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (hint != null) {
            Text(hint, color = ArcadeColors.Muted, fontSize = 12.sp)
        }
    }
}

/** The fleet roster with each ship struck through once it is sunk. */
@Composable
fun FleetRoster(sunkIds: Set<String>, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (type in BattleshipLogic.FLEET) {
            val down = type.id in sunkIds
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (down) ArcadeColors.PinkDeep else ArcadeColors.Chip)
                    .border(
                        1.dp,
                        if (down) ArcadeColors.PinkDeep else ArcadeColors.Frame,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "${type.label} ${type.length}",
                    color = if (down) Color.White else ArcadeColors.InkSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
