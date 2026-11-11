# Chut — filtre d'appels indésirables pour Android

Quand un appel indésirable arrive, **le téléphone ne sonne pas** : l'appel est raccroché
automatiquement (ou mis en silence, au choix) et tu reçois seulement une petite notification
discrète « Appel indésirable bloqué », avec deux boutons : **Pas un spam** et **Rappeler**.

Android 10 ou plus récent, dans tous les pays. Interface en anglais, français, allemand,
espagnol et italien (selon la langue du téléphone). Les règles locales fonctionnent sans
internet ; les vérifications en ligne sont facultatives.

Pour ajouter les plages officielles de démarchage d'un autre pays : une ligne dans
`OFFICIAL_TELEMARKETING` (`SpamRules.kt`).

## Ce qui est filtré

| Règle | Par défaut |
|---|---|
| Plages officielles réservées au démarchage, bloquées où que tu sois : France (+33 162, 163, 270, 271, 377, 378, 424, 425, 568, 569, 948, 949, Outre-mer 9475 à 9479) et Espagne (+34 400, depuis le 17 octobre 2026) | ✅ activé |
| Numéros signalés comme usurpés par l'opérateur (vérification STIR/SHAKEN, si l'opérateur la fournit) | ✅ activé |
| Ta liste noire (numéros complets ou préfixes, ex. `+44`, `+225`) | ✅ toujours |
| Numéros masqués | désactivé |
| Mode strict : tout numéro absent de tes contacts | désactivé |

### Vérification en ligne (PhoneBlock)
Si aucune règle locale ne tranche, l'appli interroge la base communautaire gratuite
[PhoneBlock](https://phoneblock.net) : un numéro signalé au moins 3 fois (ou 1 fois en mode
« plus sévère ») est bloqué, d'où qu'il vienne. Réponse attendue en 2,5 s maximum, sinon le
téléphone sonne normalement ; les résultats sont mis en cache (7 jours pour un spam, 1 jour
sinon). Seuls les numéros absents des contacts sont envoyés. Une clé API PhoneBlock est
facultative.

### Annuaire inversé
- Numéros suisses (+41) et du Liechtenstein : le nom est cherché dans l'annuaire officiel
  tel.search.ch. Pendant qu'un inconnu sonne, une notification « Qui appelle ? » affiche le nom ;
  pour un appel bloqué, le nom apparaît dans la notification et l'historique.
- Tous pays, avec une clé Google Maps Platform (facultative) : nom, ville et **note Google**
  des entreprises (seulement si la fiche Google porte exactement ce numéro). 1 000 recherches
  gratuites par mois, payant au-delà.
- Sinon : bouton « Qui est-ce ? » qui lance une recherche web du numéro.

### Mode « J'attends un appel »
Un bouton laisse tout sonner pendant 2 h (livreur, médecin, recruteur…), sauf ta liste noire.

Ce qui **sonne toujours** : tes contacts et ta liste blanche. Option : laisser passer un numéro
qui rappelle dans les 3 minutes (au cas où ce serait urgent).

Un écran « Tester un numéro » permet de vérifier une règle sans attendre un vrai appel.

## Compiler l'APK

### Option A — Android Studio (sur un ordinateur)
1. Décompresser le dossier, puis **File › Open** dans Android Studio.
2. Attendre la synchronisation Gradle.
3. **Build › Build App Bundle(s) / APK(s) › Build APK(s)**.
4. L'APK se trouve dans `app/build/outputs/apk/`. Le copier sur le téléphone et l'ouvrir
   (autoriser l'installation depuis des sources inconnues si demandé).

### Option B — GitHub, sans rien installer
1. Créer un dépôt GitHub (privé si tu veux) et y envoyer tout le contenu de ce dossier,
   y compris le dossier caché `.github`.
2. GitHub compile automatiquement (onglet **Actions** › « Compiler l'APK »).
3. Une fois terminé (≈ 3 min), télécharger l'artefact **Chut-apk** depuis la page du build,
   le dézipper, et installer l'APK sur le téléphone.

## Activer la protection
1. Ouvrir Chut › **Activer la protection**.
2. Choisir **Chut** comme « Appli d'identification de l'appelant et anti-spam ».
3. Autoriser les notifications et l'accès aux contacts.

Si la fenêtre n'apparaît pas (certains Samsung / Xiaomi) : **Paramètres › Applications ›
Applications par défaut › Appli d'identification de l'appelant et anti-spam › Chut**.

## Fichiers principaux
- `SpamScreeningService.kt` — appelé par Android avant la sonnerie ; décide de couper ou non.
- `SpamRules.kt` — les règles de détection (préfixes, listes, contacts).
- `Notifier.kt` — la notification discrète et ses boutons.
- `MainActivity.kt` — l'écran de réglages, les listes, l'historique et le test.
