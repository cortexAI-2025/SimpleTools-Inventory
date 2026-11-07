# Préparation Google Play

- Package `com.simpletools.inventory`, version `1.3.0`, code de version `2`, cible Android 16 (API 36). Vérifier que ce code est supérieur à celui des bundles déjà enregistrés pour ce package.
- Le workflow Android produit un AAB de release non signé pour contrôle. Pour le téléverser, compiler avec la clé **publique** de licence Play de cette application et signer l’AAB avec la clé d’upload existante. Garder le keystore et ses mots de passe en dehors du dépôt.
- Configurer le produit à achat unique `simpletools_inventory_pro_lifetime`, option d’achat non consommable, 4,99 € TTC pour la France. Désactiver l’achat multiple et laisser une option d’achat active pour ce produit. Le prix affiché est celui de Google Play.
- Passer la clé publique RSA Base64 à Gradle via `-PinventoryPlayPublicKey=...` ou `ORG_GRADLE_PROJECT_inventoryPlayPublicKey`. La clé d’upload et la clé publique de licence ont des usages distincts. Pour un build CI, définir la variable de dépôt `INVENTORY_PLAY_PUBLIC_KEY`. Sans clé publique, la compilation désactive Billing.
- La CI contrôle l’alignement 16 Kio du ZIP APK et de ses bibliothèques natives 64 bits.
- Pour la recette, utiliser la piste de test interne, un compte de test autorisé pour la piste et configuré comme testeur de licence, puis installer le build correspondant depuis Google Play.
- Vérifier la fiche de confidentialité et la section Sécurité des données à partir du bundle effectivement signé. Le stock, les photos et le journal sont locaux. Google Play traite les transactions. Le sélecteur de fichiers peut proposer des destinations externes choisies par l’utilisateur.

## Tests sur la piste interne

- [ ] Une installation vierge affiche FREE et le prix localisé correspondant au produit.
- [ ] Carte test approuvée. Un achat active PRO, puis une seconde demande ne crée pas un nouvel achat.
- [ ] Tester l’annulation du dialogue, une carte refusée, une transaction différée approuvée et une transaction différée annulée.
- [ ] Fermer et rouvrir en mode avion après validation. Les fonctions PRO locales restent accessibles.
- [ ] Réinstaller sur le même compte. Restaurer l’achat. Une réinstallation efface normalement les données locales de l’ancienne installation, à récupérer depuis une sauvegarde exportée.
- [ ] Rembourser avec révocation sur une installation ayant plus de 50 articles. Rouvrir connecté et vérifier le retour à FREE, la conservation du catalogue et le rejet d’une création supplémentaire.
