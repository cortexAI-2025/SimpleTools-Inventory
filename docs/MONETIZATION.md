# SimpleTools Inventory V1.3

| Usage | FREE | PRO |
|---|---|---|
| Fiches du catalogue | 50, archives incluses | Sans plafond applicatif |
| Catégories et emplacements | 50 catégories, un nouvel emplacement si aucun n’existe | Sans plafond applicatif |
| Consultation, recherche, photos, prix, scanner, NFC, mouvements IN/OUT | Oui | Oui |
| Inventaires nommés, duplication, archivage, seuils, corrections motivées, comptages | Accès en lecture aux données déjà présentes | Oui |
| Import CSV, export CSV/JSON/PDF, sauvegarde et restauration, étiquettes, analyses | | Oui |

En FREE, la limite de création est vérifiée dans la transaction Room. Les articles existants restent consultables et modifiables, les champs PRO existants sont conservés et les fiches archivées peuvent être réactivées. Le quota compte toutes les fiches. Les inventaires, emplacements et catégories déjà créés restent visibles après révocation. Les mouvements IN/OUT ne sont pas plafonnés.

## Achat et fonctionnement hors ligne

Le produit non consommable `simpletools_inventory_pro_lifetime` est prévu à 4,99 € TTC en France. L’interface affiche le prix localisé fourni par Google Play. Le flux ne consomme jamais l’achat.

Le client rapproche l’achat du package et du produit, vérifie la signature RSA du justificatif Google Play, exige l’état PURCHASED, puis confirme l’achat via Billing si nécessaire. PENDING n’active aucun accès. Une compilation sans clé publique Play désactive les achats et reste utilisable en FREE.

Un justificatif signé n’est enregistré dans DataStore qu’après confirmation. À chaque démarrage à froid, sa signature est revérifiée pour autoriser l’accès local hors ligne. Une erreur de connexion conserve le dernier justificatif valide. Une réponse Play réussie qui ne contient plus le produit acheté retire le droit PRO, sans supprimer de données. Un remboursement doit retirer le droit d’achat côté Play pour être détecté ainsi. La détection dépend d’une réponse Play à jour et peut être différée hors ligne. La restauration interroge les achats actifs sur le compte Google Play utilisé par l’installation.

La vérification est locale, avec les limites de sécurité d’un client Android modifiable. Aucun compte applicatif, serveur ou transfert automatique des données de stock. Le stockage de l’inventaire et le justificatif sont exclus de la sauvegarde Android automatique.

## Fichiers et indicateurs

- CSV UTF-8 à point-virgule. L’export du catalogue, des mouvements et des écarts est lisible dans Excel. L’import accepte l’en-tête `article` puis les colonnes facultatives `code;reference;categorie;emplacement;quantite;unite;achat;vente;devise;inventaire;fournisseur;description;seuil`. Les mouvements et comptages d’un CSV exporté sont ignorés à l’import. Les fiches importées sont nouvelles. Les conflits de code et les valeurs invalides annulent le lot avant écriture. Pour préserver tous les identifiants, codes secondaires, photos et mouvements, utiliser la sauvegarde JSON.
- La restauration fusionne des données compatibles sans écraser une fiche divergente. Elle accepte les sauvegardes V1 et V2. Le plafond de fichier est 20 Mio, dont 10 Mio de JPEG avant encodage. Le CSV est limité à 4 Mio.
- Valorisation au prix d’achat par devise, à partir des fiches actives avec prix renseigné. Les prix manquants sont signalés. Les entrées, sorties et corrections sont regroupées par unité et mois civil dans le fuseau de l’appareil. Les mouvements V1 sans unité sont affichés séparément. La rotation est calculée sur le nombre de mouvements du mois.
- Chaque fiche appartient à un inventaire et possède un emplacement principal. La première étiquette d’une fiche sans code lui attribue un identifiant scannable. Les formats générés sont Code 128 et QR, avec export PDF et impression Android.
