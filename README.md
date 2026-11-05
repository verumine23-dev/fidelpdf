# FidelPDF

Application Android qui reconstruit un document scanné en **vrai PDF vectoriel** (texte réel, polices embarquées, traits et aplats vectoriels), avec détourage des tampons, signatures, logos et photos.

Tout le traitement se fait **sur le téléphone** (OCR ML Kit embarqué, aucun envoi de document).

## Compiler avec GitHub

1. Créez un dépôt GitHub et envoyez-y tout le contenu de ce dossier (branche `main`).
2. Onglet **Actions** → workflow **Build APK** (il se lance à chaque push, ou via *Run workflow*).
3. Une fois terminé, ouvrez l'exécution et téléchargez l'artefact **FidelPDF-apk** : il contient `app-debug.apk`, installable directement.

## Ce que fait le moteur

| Étape | Technique |
|---|---|
| Redressement | Estimation de l'inclinaison par projection, rotation automatique |
| Texte | OCR ML Kit (latin, accents français), boîte d'encre mesurée au pixel |
| Polices | Rendu et comparaison sur Arial, Times, Courier, Calibri, Cambria (métriques compatibles, 20 variantes), vote sur la famille dominante du document |
| Position | Taille et étirement calés pour que chaque texte épouse sa boîte d'encre |
| Tableaux | Détection des traits horizontaux/verticaux (épaisseur, couleur, jonctions) |
| Couleurs | Couleur du texte, des traits, aplats de cellules avec bords affinés au pixel |
| Tampons / signatures | Intensité de l'encre → transparence ; le texte noir sous un tampon bleu est conservé |
| Logos / photos | Suppression du fond depuis les bords (logos) ou recadrage simple (photos) |
| Contrôle | Score de fidélité, carte des écarts, comparateur à curseur |

## Limites connues (version 1)

- Une page par document ; images uniquement (pas d'import PDF).
- Lignes en pointillés, texte vertical, texte manuscrit non vectorisés.
- Les tampons et signatures restent des images transparentes (haute résolution), le reste est vectoriel.
- Polices rares : la plus proche est choisie parmi les 5 familles embarquées.
