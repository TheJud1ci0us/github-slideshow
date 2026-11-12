package com.ascolta.app.ui

import com.ascolta.app.R
import com.ascolta.app.data.FishGame.Decoration
import com.ascolta.app.data.FishGame.Species

/** Names and drawings of the Aquarium (generated list: explicit, so resource shrinking keeps them). */
internal class FishArt(val name: Int)

internal val FISH_ART: Map<Species, FishArt> = mapOf(
    Species.CLOWNFISH to FishArt(R.string.aq_fish_clownfish),
    Species.BLUETANG to FishArt(R.string.aq_fish_bluetang),
    Species.GOLDFISH to FishArt(R.string.aq_fish_goldfish),
    Species.ANGELFISH to FishArt(R.string.aq_fish_angelfish),
    Species.PUFFERFISH to FishArt(R.string.aq_fish_pufferfish),
    Species.SHARK to FishArt(R.string.aq_fish_shark),
    Species.BETTA to FishArt(R.string.aq_fish_betta),
    Species.NEONTETRA to FishArt(R.string.aq_fish_neontetra),
    Species.BUTTERFLYFISH to FishArt(R.string.aq_fish_butterflyfish),
    Species.ANGLERFISH to FishArt(R.string.aq_fish_anglerfish),
)

internal class DecoArt(val art: Int, val name: Int)

internal val DECO_ART: Map<Decoration, DecoArt> = mapOf(
    Decoration.SEAWEED to DecoArt(R.drawable.aq_deco_seaweed, R.string.aq_deco_seaweed),
    Decoration.ROCKS to DecoArt(R.drawable.aq_deco_rocks, R.string.aq_deco_rocks),
    Decoration.REDPLANT to DecoArt(R.drawable.aq_deco_redplant, R.string.aq_deco_redplant),
    Decoration.STARFISH to DecoArt(R.drawable.aq_deco_starfish, R.string.aq_deco_starfish),
    Decoration.CORAL_PINK to DecoArt(R.drawable.aq_deco_coral_pink, R.string.aq_deco_coral_pink),
    Decoration.CORAL_BRAIN to DecoArt(R.drawable.aq_deco_coral_brain, R.string.aq_deco_coral_brain),
    Decoration.CLAM to DecoArt(R.drawable.aq_deco_clam, R.string.aq_deco_clam),
    Decoration.AMPHORA to DecoArt(R.drawable.aq_deco_amphora, R.string.aq_deco_amphora),
    Decoration.ANCHOR to DecoArt(R.drawable.aq_deco_anchor, R.string.aq_deco_anchor),
    Decoration.CHEST to DecoArt(R.drawable.aq_deco_chest, R.string.aq_deco_chest),
    Decoration.CASTLE to DecoArt(R.drawable.aq_deco_castle, R.string.aq_deco_castle),
    Decoration.SHIP to DecoArt(R.drawable.aq_deco_ship, R.string.aq_deco_ship),
)
