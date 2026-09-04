
// trimm currently has no durable remote project database. This store tracks deletion
// progress for the process lifetime. A database-backed implementation can replace it
// without weakening lifecycle ordering or idempotency.
class VolatileAccountStore {
  constructor() { this.deletions = new Map(); }
  async exportUser() { return { projects: [], providerConnections: [] }; }
  async beginDeletion(value) {
    if (!this.deletions.has(value.operationId)) this.deletions.set(value.operationId, { ...value, status: 'pending', completedSteps: [] });
    return this.deletions.get(value.operationId);
  }
  async recordDeletionStep(id, step) { this.deletions.get(id).completedSteps.push(step); }
  async failDeletion(id, step) { Object.assign(this.deletions.get(id), { status: 'failed', failedStep: step }); }
  async removeProviderConnections() {}
  async removeUserData() {}
  async completeDeletion(id, completedAt) { Object.assign(this.deletions.get(id), { status: 'completed', completedAt, failedStep: undefined }); }
}

export {  VolatileAccountStore  };
