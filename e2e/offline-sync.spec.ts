import { test, expect } from '@playwright/test';
import { ChatAppTestHelper } from './test-helpers';

test.describe('Chat App - Offline Cache and Sync', () => {
  test.describe.configure({ mode: 'serial' });

  test('Loads cached chapter messages while offline without refresh/login cycle', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    const owner = await helper.registerAndLogin('offline-cache-owner');
    const sender = await helper.registerAndLogin('offline-cache-sender');

    const chapterA = `Offline Cache A ${Date.now()}`;
    const chapterB = `Offline Cache B ${Date.now()}`;
    const chapterAId = await helper.createChapter(owner.sessionToken, chapterA, 'private');
    const chapterBId = await helper.createChapter(owner.sessionToken, chapterB, 'private');
    await helper.addChapterMember(owner.sessionToken, chapterAId, sender.userId, 'editor');

    const cachedContent = `Cached while online ${Date.now()}`;
    const msg = await helper.createMessage(sender.sessionToken, cachedContent);
    await helper.addMessageToChapter(sender.sessionToken, chapterAId, msg);

    await helper.navigateToApp();
    await helper.loginViaUI(owner.username, owner.password);
    await helper.navigateToMessaging();

    await helper.selectChapterByName(chapterA);
    await expect(page.locator('.message-content', { hasText: cachedContent })).toBeVisible({ timeout: 5000 });

    await page.context().setOffline(true);

    await helper.selectChapterByName(chapterB);
    await helper.selectChapterByName(chapterA);

    await expect(page.locator('.chat-thread .panel-head')).toContainText(`# ${chapterA}`, { timeout: 5000 });
    await expect(page.locator('.message-content', { hasText: cachedContent })).toBeVisible({ timeout: 5000 });

    // keep explicit usage for setup completeness
    expect(chapterBId).toBeGreaterThan(0);
    await page.context().setOffline(false);
  });

  test('Allows single offline draft edit and syncs with client edit timestamp once online', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    const owner = await helper.registerAndLogin('offline-sync-owner');

    const chapterTitle = `Offline Sync ${Date.now()}`;
    const chapterId = await helper.createChapter(owner.sessionToken, chapterTitle, 'private');

    await helper.navigateToApp();
    await helper.loginViaUI(owner.username, owner.password);
    await helper.navigateToMessaging();
    await helper.selectChapterByName(chapterTitle);
    await expect(page.locator('.chat-thread .panel-head')).toContainText(`# ${chapterTitle}`, { timeout: 5000 });
    await expect(page.locator('.status-line')).toContainText('Loaded', { timeout: 5000 });

    await page.context().setOffline(true);

    const firstContent = `Offline draft ${Date.now()}`;
    await page.locator('.composer-row .composer-input').fill(firstContent);
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.locator('.status-line')).toContainText('Offline: queued one message for sync', { timeout: 5000 });
    await expect(page.locator('.message-content', { hasText: firstContent })).toBeVisible({ timeout: 5000 });

    const secondContent = `Offline second ${Date.now()}`;
    await page.locator('.composer-row .composer-input').fill(secondContent);
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.locator('.status-line')).toContainText('Only one offline pending message is supported', { timeout: 5000 });

    const offlineEntry = page.locator('.message-entry', { has: page.locator('.message-content', { hasText: firstContent }) }).first();
    await offlineEntry.locator('.message-link').click();

    const editedContent = `${firstContent} (edited offline)`;
    await page.locator('.inspector-card textarea').fill(editedContent);
    await page.getByRole('button', { name: 'Save Edit' }).click();
    await expect(page.locator('.status-line')).toContainText('Updated offline pending message', { timeout: 5000 });

    await page.context().setOffline(false);
    await page.locator('.status-line').click();
    await expect(page.locator('.status-line')).toContainText('Synced offline message as #', { timeout: 10000 });

    const messages = await helper.listChapterMessages(owner.sessionToken, chapterId, 50);
    const synced = messages.find(m => m.content === editedContent);
    expect(synced).toBeTruthy();

    const byId = await helper.getMessageById(owner.sessionToken, synced!.id);
    expect(byId.clientEditedAtEpochMillis).toBeTruthy();
    expect(byId.version).toBeGreaterThanOrEqual(1);
  });
});
