# 16. Design system du Chat Workspace

## 16.1 Direction
Interface premium, sobre, technique, sans surcharge.
Cortana doit paraître être un outil de travail personnel, pas un tableau de bord SaaS générique.

## 16.2 Surfaces
- Background
- Surface
- Elevated Surface
- Composer Surface
- Tool/Activity Surface
- Warning Surface
- Critical Surface

Toutes via tokens de thème, jamais couleurs codées dans composants.

## 16.3 Radius
Cohérence :
- petits contrôles;
- cartes;
- composer;
- modal.
Éviter mélange arbitraire.

## 16.4 Spacing
Grille 4/8dp.
Touch targets Android >= recommandation plateforme.

## 16.5 Message width
Assistant : largeur de lecture limitée sur grand écran.
User : alignement distinct mais pas bulle énorme.
Code/table peuvent dépasser dans container scrollable.

## 16.6 Animation
- transitions 120-220ms;
- respecter reduce motion;
- pas de clignotement pendant streaming.

## 16.7 Activity
États via icon + texte, pas couleur seule.

## 16.8 Empty state
Nouveau chat :
- salutation courte;
- quelques actions utiles;
- projets/récents;
- pas 20 cartes.

## 16.9 Progressive disclosure
Options avancées cachées jusqu’au besoin.
Le composer ne doit pas devenir une barre d’outils permanente.

## 16.10 Tablet posture
Landscape : sidebar + chat + right panel.
Portrait : chat + panneaux overlays.
Keyboard visible : conserver composer accessible.
