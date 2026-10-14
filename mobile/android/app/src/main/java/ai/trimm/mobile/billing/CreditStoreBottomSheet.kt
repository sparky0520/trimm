package ai.trimm.mobile.billing

import ai.trimm.mobile.ui.*
import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditStoreBottomSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val balance by BillingManager.balance.collectAsStateWithLifecycle()
    val costs by BillingManager.costs.collectAsStateWithLifecycle()
    val packages by BillingManager.packages.collectAsStateWithLifecycle()
    val isPurchasing by BillingManager.isPurchasing.collectAsStateWithLifecycle()
    val isLoading by BillingManager.isLoading.collectAsStateWithLifecycle()
    val errorMessage by BillingManager.errorMessage.collectAsStateWithLifecycle()

    var selectedPackageId by remember(packages) {
        mutableStateOf(packages.firstOrNull { it.id == BillingManager.PACKAGE_300 }?.id ?: packages.firstOrNull()?.id ?: "")
    }
    var purchaseSuccessCredits by remember { mutableStateOf<Int?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        BillingManager.fetchCredits()
        BillingManager.fetchOfferings()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ink,
        contentColor = Cream,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                // Top close bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .background(Raised, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Muted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Balance header section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color(0xFFFACC15),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "TRIMM CREDITS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Muted,
                            letterSpacing = 1.4.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "$balance",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = Cream,
                            lineHeight = 48.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "credits",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Muted,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = "Powering speech transcription and AI conversational video edits. Your video files stay securely on device.",
                        fontSize = 13.sp,
                        color = Muted,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Packages List
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    packages.forEach { pkg ->
                        val isSelected = selectedPackageId == pkg.id
                        PackageCard(
                            pkg = pkg,
                            isSelected = isSelected,
                            onSelect = { selectedPackageId = pkg.id }
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Main Action Button
                val selectedPkg = packages.firstOrNull { it.id == selectedPackageId }
                if (selectedPkg != null) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Button(
                            onClick = {
                                if (activity != null) {
                                    scope.launch {
                                        try {
                                            BillingManager.purchase(activity, selectedPkg)
                                            purchaseSuccessCredits = selectedPkg.credits
                                        } catch (e: Exception) {
                                            localError = e.localizedMessage
                                        }
                                    }
                                }
                            },
                            enabled = !isPurchasing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Cream,
                                contentColor = DarkText,
                                disabledContainerColor = Raised,
                                disabledContentColor = Muted
                            )
                        ) {
                            if (isPurchasing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = DarkText,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.ShoppingBag,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Top Up ${selectedPkg.credits} Credits • ${selectedPkg.priceString}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Usage Breakdown Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(Workspace, RoundedCornerShape(14.dp))
                        .border(1.dp, Border.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "HOW CREDITS ARE USED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Muted,
                        letterSpacing = 1.2.sp
                    )

                    Spacer(Modifier.height(14.dp))

                    CreditCostRow(
                        icon = Icons.Default.AutoAwesome,
                        title = "Timeline Edit & AI Chat",
                        cost = "${costs.chatCost} credits"
                    )

                    HorizontalDivider(
                        color = Border.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )

                    CreditCostRow(
                        icon = Icons.Default.GraphicEq,
                        title = "Audio Transcription",
                        cost = "${costs.transcriptionCost} credits"
                    )

                    HorizontalDivider(
                        color = Border.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )

                    CreditCostRow(
                        icon = Icons.Default.PlayCircle,
                        title = "Real-time On-Device Preview",
                        cost = "Free (0 credits)"
                    )

                    HorizontalDivider(
                        color = Border.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )

                    CreditCostRow(
                        icon = Icons.Default.Share,
                        title = "High-Res MediaStore Export",
                        cost = "Free (0 credits)"
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Footer / Compliance
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextButton(
                        onClick = {
                            scope.launch { BillingManager.restorePurchases() }
                        },
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Muted,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "Restore Purchases",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Muted
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "Credits never expire and remain attached to your Trimm account. Consumable in-app purchases are charged to your Google Play account.",
                        fontSize = 11.sp,
                        color = Muted.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        lineHeight = 15.sp
                    )
                }
            }

            // Purchase success dialog
            if (purchaseSuccessCredits != null) {
                AlertDialog(
                    onDismissRequest = {
                        purchaseSuccessCredits = null
                        onDismiss()
                    },
                    containerColor = Raised,
                    title = {
                        Text(
                            text = "Credits Added!",
                            color = Cream,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            text = "$purchaseSuccessCredits credits have been successfully added to your account.",
                            color = CreamRaised
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                purchaseSuccessCredits = null
                                onDismiss()
                            }
                        ) {
                            Text("Done", color = Cream, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }

            // Error dialog
            val activeError = localError ?: errorMessage
            if (activeError != null) {
                AlertDialog(
                    onDismissRequest = { localError = null },
                    containerColor = Raised,
                    title = {
                        Text(
                            text = "Purchase Notice",
                            color = Cream,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            text = activeError,
                            color = CreamRaised
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { localError = null }) {
                            Text("OK", color = Cream, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PackageCard(
    pkg: CreditPackage,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Raised else Workspace)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) Cream.copy(alpha = 0.8f) else Border.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Radio indicator
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(
                    width = 2.dp,
                    color = if (isSelected) Cream else Border,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Cream, CircleShape)
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${pkg.credits} Credits",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Cream
                )

                if (pkg.badge != null) {
                    Spacer(Modifier.width(8.dp))
                    val isBestValue = pkg.badge == "BEST VALUE"
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isBestValue) Color(0xFFFACC15).copy(alpha = 0.2f) else Cream.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(50)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = pkg.badge,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isBestValue) Color(0xFFFACC15) else Cream
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))

            Text(
                text = pkg.description,
                fontSize = 12.sp,
                color = Muted,
                maxLines = 1
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = pkg.priceString,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) Cream else Muted
        )
    }
}

@Composable
private fun CreditCostRow(
    icon: ImageVector,
    title: String,
    cost: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            color = CreamRaised,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Spacer(Modifier.weight(1f))
        Text(
            text = cost,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Muted,
            maxLines = 1,
            softWrap = false
        )
    }
}
