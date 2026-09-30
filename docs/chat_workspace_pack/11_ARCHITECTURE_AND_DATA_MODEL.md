# 11. Architecture et modèle de données

## 11.1 Principe
Le Chat Workspace est une couche présentation + contrats UI.
Il consomme les services existants.

```text
Chat UI
  |
ChatViewModel / ChatController
  |
ConversationService
  +-> TaskOrchestrator
  +-> ModelGateway
  +-> ContextEngine
  +-> MemoryService
  +-> ToolRegistry / PolicyEngine
  +-> ArtifactService
  +-> VoiceService
  +-> Search/Research
```

## 11.2 Entités
À adapter à Room existant.

### conversations
- id
- projectId
- title
- mode
- modelPreference
- createdAt
- updatedAt
- archived
- pinned
- incognito

### messages
- id
- conversationId
- parentId
- branchId
- role
- status
- createdAt
- modelRouteRef
- runId

### message_parts
- id
- messageId
- index
- type
- payloadJson
- artifactRef
- sourceRef

### branches
- id
- conversationId
- rootMessageId
- label
- createdAt

### drafts
- conversationId
- text
- attachmentRefs
- updatedAt

### pins
- conversationId
- targetType
- targetId

### context_checkpoints
- id
- conversationId
- summary
- coveredUntilMessageId
- createdAt
- modelRef

## 11.3 Events
- MessageQueued
- GenerationStarted
- StreamDelta
- ToolStarted
- ApprovalRequired
- ToolCompleted
- ArtifactCreated
- GenerationStopped
- GenerationCompleted
- ContextCompacted
- ModelChanged
- BranchCreated.

## 11.4 Stream IDs
Chaque generation possède `runId`.
Chaque event a `sequence`.
UI déduplique.

## 11.5 Persistence
Draft, branch, current variant, scroll anchor facultatif et unread state doivent survivre au restart.

## 11.6 Migration
Additive, testée.
Ne pas effacer l’historique v1.2/VNext.

## 11.7 Performance
- paging conversations;
- virtualized timeline;
- lazy load old message parts;
- thumbnails;
- cache markdown AST si pertinent;
- éviter recompose globale Android.

## 11.8 Security
- raw HTML sandbox/disable;
- links sanitized;
- file URI safe;
- tool actions policy-bound;
- secret redaction;
- untrusted source framing.
