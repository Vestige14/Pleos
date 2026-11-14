package app.memoryvault.share

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object ShareImageText {
    fun lines(draft: ShareDraft): List<String> = buildList {
        add(LocalDate.parse(draft.date).format(DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN)))
        draft.items.forEach { item ->
            add("${item.order}. ${item.placeName}")
            item.caption?.let { add(it) }
        }
    }
}
