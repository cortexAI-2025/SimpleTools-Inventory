# SimpleTools Inventory · by AIWORKPAY

Application Android locale pour les stocks et les inventaires. Android 10 ou plus récent, package `com.simpletools.inventory`, version 1.0.0.

## Compilation

Utiliser JDK 17 et Android SDK 35. Lancer `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Avec un appareil ou émulateur démarré, lancer `./gradlew :app:connectedDebugAndroidTest`. GitHub Actions fournit l’artefact **SimpleTools-Inventory-Android-APK** avec la somme SHA-256.

## Règles de données

Les quantités et prix sont des entiers exprimés en millièmes. Le tableau de bord distingue les unités pour éviter d’additionner des pièces et des kilogrammes. Les mouvements restent dans le journal après suppression d’une fiche. Les codes et les identifiants NFC sont uniques, y compris pour les fiches archivées.

Chaque session fige les quantités de départ. À la validation, les lignes non comptées restent sans correction. Si une quantité a changé entre le début et la validation, la transaction est rejetée et une nouvelle session peut être créée.

Le scanner utilise le modèle ML Kit embarqué. Le NFC lit un identifiant NDEF de type MIME `application/vnd.simpletools.inventory` contenant de 1 à 120 caractères ASCII alphanumériques ou `._:-`. À défaut, il peut utiliser l’empreinte SHA-256 de l’UID quand celui-ci est disponible. Certains tags ont un UID dynamique ou non lisible. L’association sert à retrouver une fiche.

### Fichiers

- CSV UTF-8 avec BOM, séparateur point-virgule, catalogue, mouvements et comptages.
- Rapport JSON `simpletools-inventory-report`, avec `schemaVersion: 1`, `articles`, `labels` (catégories et emplacements, distingués par `kind`), `movements`, `sessions` et `counts`. Dates en millisecondes Unix, quantités et prix en millièmes.
- PDF paginé par stock ou session de comptage.
- Sauvegarde JSON séparée `simpletools-inventory-backup`, mêmes données et dictionnaire `photos` qui associe les UUID aux JPEG encodés en Base64. Taille maximale du fichier de 20 Mio, avec plafond cumulé de 10 Mio de JPEG avant encodage. Réimport identique possible. Les conflits rejettent l’ensemble de l’import. Thème, langue et choix de notification sont des préférences propres à l’appareil.

Les photos sont stockées dans une table privée distincte et chargées à la demande. Les rapports et sauvegardes sont enregistrés via le sélecteur Android.

## Recette physique Galaxy A14 · Android 15

- [ ] Installer l’APK et démarrer en mode avion.
- [ ] Créer une catégorie et un emplacement. Ajouter une fiche avec photo, catégorie et emplacement.
- [ ] Scanner un code réel, créer une fiche à partir du code puis scanner à nouveau pour retrouver la fiche.
- [ ] Partir de 20, entrer 10 et sortir 4. Vérifier le stock final de 26 et le journal.
- [ ] Régler le seuil à 30 et retrouver l’article dans les alertes.
- [ ] Créer une session, scanner et compter plusieurs articles. Quitter, reprendre, vérifier les écarts et confirmer la correction.
- [ ] Exporter CSV et PDF, ouvrir les fichiers et vérifier accents, quantités et écarts.
- [ ] Sauvegarder, restaurer dans une installation propre ou un second profil, vérifier les photos et mouvements.
- [ ] Fermer et rouvrir. Vérifier la persistance.
- [ ] Tester lecture et association NFC si l’appareil dispose du matériel.
- [ ] Vérifier le parcours principal sans Wi-Fi ni données mobiles.
