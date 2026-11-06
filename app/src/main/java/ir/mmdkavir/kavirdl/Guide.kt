package ir.mmdkavir.kavirdl

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Onboarding(onDone: () -> Unit, onSettings: () -> Unit, onGuide: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides androidx.compose.ui.graphics.Color.White) {
    Column(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101014)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (page) {
                    0 -> {
                        Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(110.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("خوش آمدی", style = MaterialTheme.typography.headlineMedium)
                        Text("KavirDL", style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "دانلودر ساده برای ویدیو، آهنگ و عکس. لینک رو بذار، بقیه‌اش با ما.",
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { scope.launch { pager.animateScrollToPage(1) } }) { Text("شروع") }
                    }
                    1 -> {
                        Text("درباره برنامه", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "• دانلود از یوتیوب، اینستاگرام، تیک‌تاک، اسپاتیفای و پینترست\\n" +
                                "• چند دانلود همزمان با درصد، سرعت و زمان باقی‌مانده\\n" +
                                "• پخش داخل خود برنامه و ذخیره در گالری یا پوشه دلخواه\\n" +
                                "• تاریخچه‌ای که با بستن برنامه پاک نمی‌شه".replace("\\n", "\n"),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "چرا حساب گیت‌هاب خودت؟ دانلودها روی GitHub Actions ریپوی خودت اجرا می‌شن؛ " +
                                "پس فیلترینگ گوشی دور زده می‌شه، به سهمیه رایگان خودت وصله و هیچ‌کس دیگه‌ای به فایل‌ها یا توکنت دسترسی نداره.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> {
                        Text("شروع کار", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "برای دانلود باید یک بار اتصال گیت‌هاب خودت رو توی تنظیمات انجام بدی. اگه تازه‌کاری، اول راهنما رو بخون.",
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("رفتن به تنظیمات") }
                        OutlinedButton(onClick = onGuide, modifier = Modifier.fillMaxWidth()) { Text("خوندن راهنمای نصب") }
                        TextButton(onClick = onDone) { Text("بعداً") }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) {
                Spacer(
                    Modifier.size(if (pager.currentPage == it) 12.dp else 8.dp).background(
                        if (pager.currentPage == it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape
                    )
                )
            }
        }
    }
    }
}

@Composable
private fun Link(label: String, url: String) {
    val ctx = LocalContext.current
    TextButton(onClick = {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
    }) { Text("$label\n$url", style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun Section(title: String, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
            }
        }
    }
}

@Composable
fun GuideScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("راهنمای قدم‌به‌قدم برای وصل کردن گیت‌هاب شخصی. هر بخش رو باز کن.", style = MaterialTheme.typography.bodyMedium)
        Section("الف) ساخت حساب گیت‌هاب", true) {
            Text("اگه حساب نداری، با ایمیل ثبت‌نام کن و ایمیلت رو تأیید کن.")
            Link("ثبت‌نام / ورود", "https://github.com/")
        }
        Section("ب) ساخت ریپو") {
            Text("یه ریپو بساز (مثلا KavirDL-files). می‌تونی این کار رو رد کنی و توی تنظیمات با «ساخت پیکربندی» بذاری برنامه خودش بسازه، ولی برای این کار توکن Classic لازمه.")
            Text("عمومی (Public): همه می‌تونن ریپو رو ببینن و Actions محدودیت دقیقه نداره. فایل‌های دانلودی فقط چند لحظه توی ریلیز موقت می‌مونن و بعد پاک می‌شن، ولی تا اون موقع با لینک قابل دسترسی‌ان.")
            Text("خصوصی (Private): فقط خودت می‌بینی؛ هر ماه سهمیه دقیقه Actions رایگان محدوده. برای حریم خصوصی پیشنهاد می‌شه.")
            Link("ساخت ریپوی جدید", "https://github.com/new")
        }
        Section("ج) ساخت توکن دسترسی (PAT)") {
            Text("پیشنهاد ما: توکن Fine-grained (امن‌تر، فقط برای یک ریپو).", style = MaterialTheme.typography.titleSmall)
            Link("ساخت توکن Fine-grained", "https://github.com/settings/personal-access-tokens/new")
            Text("۱) اسم دلخواه بده و مدت انقضا انتخاب کن.\n۲) Repository access: Only select repositories و فقط ریپوی خودت رو انتخاب کن (نه All repositories).\n۳) توی Repository permissions این‌ها رو روی Read and write بذار:\n   • Contents (آپلود فایل‌ها و ریلیز موقت دانلودها)\n   • Actions (اجرا، لغو و دیدن وضعیت دانلودها)\n   • Workflows (نصب فایل‌های .github/workflows)\n   • Metadata فقط خواندنی است و خودکار فعال می‌شه.\n۴) Generate token رو بزن و توکن رو همون لحظه کپی کن؛ بعداً دیگه نشون داده نمی‌شه.")
            Text("چیزهایی که لازم نیست: Administration، Pull requests، Issues، Secrets، Pages، Environments، Webhooks و همه دسترسی‌های حساب (Account permissions). Read-only برای Contents یا Actions کافی نیست چون برنامه فایل می‌نویسه و اجرا شروع می‌کنه.")
            Text("توکن Classic (فقط اگه می‌خوای برنامه ریپو بسازه):", style = MaterialTheme.typography.titleSmall)
            Link("ساخت توکن Classic", "https://github.com/settings/tokens")
            Text("scope های repo و workflow رو تیک بزن؛ بقیه رو نزن. توکن Classic به همه ریپوهات دسترسی داره، پس بعد از راه‌اندازی می‌تونی پاکش کنی و یه Fine-grained بسازی.")
            Text("نگه‌داری امن: توکن رو برای کسی نفرست و توی اسکرین‌شات نذار. برنامه اون رو با Android Keystore رمزگذاری می‌کنه و هیچ‌وقت نشون نمی‌ده. اگه لو رفت از همین صفحه پاکش کن.")
            Link("مدیریت توکن‌ها", "https://github.com/settings/personal-access-tokens")
        }
        Section("د) وصل کردن ریپو توی برنامه") {
            Text("۱) تنظیمات رو باز کن.\n۲) توکن رو بچسبون.\n۳) لینک یا اسم ریپو رو بنویس (مثلا نام‌کاربری/ریپو). نام کاربری و شاخه پیش‌فرض خودکار از روی توکن و ریپو پیدا می‌شن.\n۴) «ذخیره و تست اتصال» رو بزن. اگه همه‌چیز درست باشه حساب و ریپو نشون داده می‌شه.\n۵) «ساخت پیکربندی» رو بزن تا ورک‌فلوها خودکار نصب بشن، بعد اگه لازم شد «تازه‌سازی وضعیت».")
            Link("مستندات رسمی توکن‌ها", "https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens")
            Link("مستندات رسمی GitHub Actions", "https://docs.github.com/en/actions")
        }
        Section("ه) رفع مشکل") {
            Text("توکن نامعتبر (401): توکن اشتباه کپی شده یا منقضی شده. توکن جدید بساز و بدون فاصله اضافه بچسبون.")
            Link("توکن‌ها", "https://github.com/settings/personal-access-tokens")
            Text("دسترسی ناکافی (403): دسترسی‌های Contents، Actions و Workflows رو روی Read and write بذار و مطمئن شو ریپوی درست انتخاب شده.")
            Text("ریپو پیدا نشد (404): اسم یا نام کاربری رو چک کن، و اینکه توکن به همون ریپو دسترسی داره. برای ریپوی خصوصی توکن باید حتماً اون ریپو رو انتخاب کرده باشه.")
            Link("ریپوهای تو", "https://github.com/?tab=repositories")
            Text("شاخه اشتباه: برنامه خودش شاخه پیش‌فرض ریپو رو استفاده می‌کنه. اگه ریپو خالیه یا شاخه اصلیش رو عوض کردی، «ساخت پیکربندی» رو دوباره بزن.")
            Text("محدودیت درخواست (rate limit، پیام 403 یا 429): چند دقیقه صبر کن و دوباره تلاش کن. حد مجاز برای توکن هر ساعت محدوده.")
            Link("اطلاعات rate limit", "https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api")
            Text("خطای شبکه: اینترنت رو چک کن. برنامه خودش چند بار دوباره تلاش می‌کنه. اگه github.com برات باز نمی‌شه، اول دسترسیت رو درست کن.")
            Link("وضعیت GitHub", "https://www.githubstatus.com/")
        }
    }
}
