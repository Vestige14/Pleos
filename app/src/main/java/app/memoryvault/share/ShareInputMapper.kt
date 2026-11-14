package app.memoryvault.share

import app.memoryvault.data.DayDraft

/** This whitelist is the only bridge from private day entities into the sharing flow. */
object ShareInputMapper {
    fun from(day: DayDraft): ShareInput = ShareInput(day.date, day.cards.map { card ->
        ShareVisitInput(card.visit.id, card.visit.date, card.visit.placeName, card.visit.arrivedAt,
            card.photos.map { SharePhotoInput(it.uri, SharePhotoState.valueOf(it.linkState.name), it.accessible) })
    }).eligibleCopy()
}
