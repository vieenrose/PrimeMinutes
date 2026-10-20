# Prime Minutes — logo (Key line) — guidelines

**Mark:** la carte de transcription dont la ligne clé sort du bord, épinglée d'un point.
Adoptée 2026-10-20 (round 2). Fichier maître : `final-symbol.svg`.

## Couleurs

| Rôle | HEX | RGB |
|---|---|---|
| Encre (tuile, monochrome) | `#1C2733` | 28 39 51 |
| Vert (ligne clé + point) | `#0A7A52` | 10 122 82 |
| Blanc (lignes) | `#FFFFFF` | — |
| Fond clair recommandé | `#F7F9FB` | 247 249 251 |

- Version 1 couleur : tout encre (`pm-mono-1c2733.svg`) ou tout blanc sur encre (`pm-white.svg`).
- Ne jamais introduire d'autre couleur que encre + vert + blanc.

## Taille minimale

| Version | Minimum |
|---|---|
| Marque complète (avec ligne épinglée) | 24 px |
| Cut petite taille (`final-symbol-small.svg`, 3 lignes) | 16 px |
| Tuile d'app (tuile pleine) | 48 px |

## Espacement libre

Marge minimale autour de la marque = **½ hauteur du point** (le point épinglé est l'unité de mesure). Sur tuile : la marque occupe ≤ 70 % de la zone visible.

## Fonds

- Autorisés : blanc, `#F7F9FB`, encre `#1C2733` (version lignes blanches), vert plein (version blanche uniquement).
- Photos/graded : passer par la tuile encre.
- Interdits : dégradés, ombres, contours ajoutés, rotation, inclinaison.

## Utilisations approuvées

- Icône d'app : `ios-icon.svg` (tuile pleine, iOS) ; adaptive Android = tuile encre + lignes blanches + ligne clé verte (`app/src/main/res/drawable/ic_launcher_*.xml`).
- Lockup horizontal : marque + « Prime Minutes » (Spectral SemiBold, lettrage +2/1000 ; fichier lockup = texte vivant, à vectoriser pour impression).
- Accent secondaire (splash, chargement) : « Distillation » (`concept-h.svg`) uniquement, jamais comme logo.

## Mésusage

- Ne pas transformer la marque en horloge, bulle de chat ou waveform.
- Ne pas séparer le point de sa ligne (le point vit avec la ligne clé).
- Ne pas mettre la ligne clé en vert sur fond blanc sans la tuile (contraste insuffisant) — sur fond clair, tout en encre.

## Fichiers

`final-symbol.svg` (maître) · `final-symbol-small.svg` (cut 16 px) · `ios-icon.svg` · `exports/` (variants noir/blanc/mono/tuile/favicon + web-icons + PNG 48→1024) · planche : `board/` · concepts archivés : `concepts*.png`.

Licence des polices du lockup : Spectral (OFL) — lettrage à vectoriser pour l'impression.
