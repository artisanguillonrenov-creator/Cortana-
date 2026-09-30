# Prompt à donner à Claude Code

Copie-colle le texte ci-dessous dans Claude Code, à la racine du projet Android de Cortana. Place le dossier `design_handoff_cortana_workspace/` dans le repo.

---

Tu vas implémenter le design « Cortana Workspace » dans l'app Android existante, en Kotlin et Jetpack Compose.

**Sources, par ordre d'autorité :**
1. `design_handoff_cortana_workspace/README.md` : lis-le en entier avant d'écrire du code.
2. `design_handoff_cortana_workspace/Cortana Workspace.dc.html` : prototype hi-fi. Toutes les valeurs exactes y sont : styles inline, textes, et données et logique dans la classe `Component` en bas du fichier.
3. Le dossier de spec `CORTANA_CHAT_WORKSPACE_CLAUDE_HANDOFF` (docs 00 à 16) pour l'architecture et les règles produit.

**Règles absolues :**
- **Ne rien inventer.** Couleurs, tailles, rayons, espacements, textes, icônes et animations viennent du README ou du prototype. Conversion : 1 px = 1 dp, 1 px de police = 1 sp. Si une valeur manque, arrête-toi et pose la question.
- **Aucune couleur ni dimension en dur** dans les composants. Tout passe par `compose/CortanaTokens.kt` : copie-le dans le module de thème, adapte le package, branche Geist, Geist Mono et Sora.
- **Icônes :** Material Symbols Rounded, graisse 300, optique 24. La liste est dans `ICONS.md`. Exporte-les en vector drawables avec ces réglages.
- **Pas de second backend.** L'interface consomme les services existants (TaskOrchestrator, PolicyEngine, MemoryService, ModelGateway…) à travers un `ChatViewModel` qui expose un `StateFlow<UiState>` (spec 11). Au besoin, une source de données factice reproduit la simulation du prototype pour les previews.
- **Statuts :** toujours une icône et un texte. **Animations :** respecter la réduction des animations. **Cibles tactiles :** 48 dp minimum.

**Ordre de travail** (fais un commit par étape) :
1. **Thème :** tokens, polices, `CortanaTheme {}`.
2. **Composants**, chacun dans `ui/components` avec une `@Preview` par état :
   - NavItem (inactif, actif, badge, point) ;
   - Toggle (44×26, 38×22, 34×20) ;
   - HeaderPill ;
   - ToolChip ;
   - FilterChip ;
   - SegmentedControl ;
   - StopButton (grand, moyen, rail ; STOP, Reprendre, neutre) ;
   - StatusChip (En cours, Approbation, En pause, Terminée) ;
   - ProgressBar ;
   - PlanCard avec StepRow (faite, en cours, en attente, à venir) ;
   - CodeBlock (coloration Kotlin, copier, agrandir) ;
   - ApprovalCard (en attente, autorisée, refusée) ;
   - StreamingDots ;
   - LogConsole ;
   - ContextTabs ;
   - InfoCard (Worker, MCP, Mémoire, Politique) ;
   - Composer (normal, voix) ;
   - UserBubble et QueuedBubble ;
   - ModelMenu ;
   - ActivityRail ;
   - ConversationRow ;
   - MemoryCard (libre, verrouillée, inactive) ;
   - SettingsRow (interrupteur, segmenté, valeur).
3. **Discussion en paysage :** 3 colonnes, en reproduisant exactement l'état initial du prototype (étape 3/6, logs de 14:25:12 à 14:25:24).
4. **Machine d'état de la tâche :** Running → AwaitingApproval → Running → Done, plus Paused et Refused. Inclut la file de messages et le bouton STOP partout, y compris la touche Échap.
5. **Discussion en portrait :** drawer, panneau en overlay, rail d'activité. Règles `WindowSizeClass` du README (≥ 1400 / 1200–1400 / < 1200 dp).
6. **Écrans Tâches, Historique, Mémoire, Réglages**, puis les écrans « Pas encore conçu ».
7. **Vérification visuelle :**
   - Pour chaque écran, une preview Compose en 1448×1086 dp (et 1086×1448 dp pour le portrait), comparée côte à côte avec le prototype ouvert dans Chrome.
   - Corrige tout écart supérieur à 2 dp ou toute couleur différente.

**Critères d'acceptation :**
- L'état initial de Discussion correspond au prototype.
- Le parcours complet fonctionne : approbation → autoriser → terminé, puis refuser → reprendre → réapprobation.
- STOP et Reprendre marchent depuis la sidebar, le panneau et le rail.
- Désactiver le mode développeur masque Développement, Worker, MCP et la répartition des tokens.
- Aucune couleur en dur (`grep -r "Color(0x" ui/` ne doit trouver que le fichier de tokens).
- Toutes les previews compilent, sans régression sur le chat actuel.
