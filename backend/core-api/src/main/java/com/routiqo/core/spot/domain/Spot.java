package com.routiqo.core.spot.domain;

import com.routiqo.core.routing.domain.RouteRequest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A curated public place on a pilot corridor. Reference data, never evidence of anyone's presence. */
public record Spot(UUID id, String name, String nameTa, SpotKind kind, RouteRequest.Coordinate location,
        String district, List<String> corridors, Set<SpotCategory> categories, SpotProvenance provenance) {
    private static final UUID NIL_ID = new UUID(0, 0);
    /** No phone numbers (7+ digits, spaces or dashes allowed between) or web/e-mail addresses in names. */
    private static final java.util.regex.Pattern CONTACT = java.util.regex.Pattern.compile(
            "\\d(?:[ -]?\\d){6,}|(?i:https?:|www\\.|@|\\.(?:com|in|net|org|co)\\b)");

    static boolean freeOfContactDetails(String name) {
        return name != null && !CONTACT.matcher(name).find();
    }

    static boolean containsTamil(String name) {
        return name != null && name.codePoints().anyMatch(
                codePoint -> Character.UnicodeBlock.of(codePoint) == Character.UnicodeBlock.TAMIL);
    }

    public Spot {
        if (id == null || NIL_ID.equals(id) || !SpotLabel.valid(name) || !SpotLabel.valid(nameTa)
                || !freeOfContactDetails(name) || !freeOfContactDetails(nameTa) || !containsTamil(nameTa)
                || kind == null || location == null || !SpotDistrict.known(district)
                || corridors == null || corridors.isEmpty() || corridors.size() > 16
                || corridors.stream().anyMatch(corridor -> !SpotCorridor.validId(corridor))
                || Set.copyOf(corridors).size() != corridors.size()
                || categories == null || categories.isEmpty()
                || categories.stream().anyMatch(Objects::isNull) || provenance == null) {
            throw new IllegalArgumentException("Invalid Spot");
        }
        corridors = List.copyOf(corridors);
        categories = Set.copyOf(categories);
    }
}
