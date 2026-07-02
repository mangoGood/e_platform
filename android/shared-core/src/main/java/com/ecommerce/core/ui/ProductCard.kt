package com.ecommerce.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ecommerce.core.model.Product

/**
 * 商品列表项卡片
 */
@Composable
fun ProductCard(
    product: Product,
    onClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(product.id) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = product.mainImage.toImageUrlOrNull(),
            contentDescription = product.name,
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF5F5F5))
        )
        Box(modifier = Modifier.padding(start = 12.dp)) {
            androidx.compose.foundation.layout.Column {
                Text(
                    text = product.name,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF333333)
                    ),
                    maxLines = 2
                )
                Text(
                    text = "已售 ${product.sales}",
                    style = TextStyle(fontSize = 12.sp, color = Color.Gray),
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = product.price.formatPriceWithSymbol(),
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF6B35)
                    ),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
