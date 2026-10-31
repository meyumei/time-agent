package com.timeagent.imports

import java.time.LocalDate

/** User-selected Wushan schedule for every imported course, regardless of campus metadata. */
object ScutImportPresets {
    const val TERM_NEWS_URL = "https://news.scut.edu.cn/2026/0901/c41a60471/page.htm"
    // Periods 1–10 follow the user's WakeUp screenshot. Period 11 is an editable public-school-index reference;
    // the official attachment currently returns 404, so it still requires explicit user review.
    const val WUSHAN_REFERENCE_URL = "https://www2.scut.edu.cn/_upload/article/files/8d/32/1dbbcf9a47729b8e2b85e4c02e01/a25ee229-19e8-4f09-afd5-3ea70b02bec6.pdf"
    const val WUSHAN_PERIODS = "1=08:00-08:45\n2=08:55-09:40\n3=10:00-10:45\n4=10:55-11:40\n5=14:30-15:15\n6=15:25-16:10\n7=16:20-17:05\n8=17:15-18:00\n9=19:00-19:45\n10=19:55-20:40\n11=20:50-21:35"

    fun firstMonday(termId: String): LocalDate? = if (termId.trim() == "2026-2027-1") LocalDate.of(2026, 8, 31) else null
}
