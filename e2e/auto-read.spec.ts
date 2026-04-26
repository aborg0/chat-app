import { test, expect } from '@playwright/test';
import { ChatAppTestHelper } from './test-helpers';

test.describe('Chat App - Auto Read Behavior', () => {
  test.describe.configure({ mode: 'serial' });

  test('Auto-read updates unread badge and separator on next interaction without refresh', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    const owner = await helper.registerAndLogin('autoread-ui-owner');
    const sender = await helper.registerAndLogin('autoread-ui-sender');

    const chapterTitle = `AutoRead UI ${Date.now()}`;
    const chapterId = await helper.createChapter(owner.sessionToken, chapterTitle, 'private');
    await helper.addChapterMember(owner.sessionToken, chapterId, sender.userId, 'editor');

    for (let i = 0; i < 3; i += 1) {
      const messageId = await helper.createMessage(sender.sessionToken, `Unread UI ${i + 1} ${Date.now()}`);
      await helper.addMessageToChapter(sender.sessionToken, chapterId, messageId);
    }

    await helper.navigateToApp();
    await helper.loginViaUI(owner.username, owner.password);
    await helper.navigateToMessaging();

    await helper.selectChapterByName(chapterTitle);
    await expect(page.locator('.unread-divider')).toBeVisible({ timeout: 5000 });
    expect(await helper.unreadBadgeTextForChapter(chapterTitle)).toBe('3');

    // No refresh button usage: interaction should auto-read fully visible messages.
    await page.locator('.status-line').click();

    await expect
      .poll(async () => {
        const state = await helper.chapterUnreadCount(owner.sessionToken, chapterId);
        return state.unreadCount;
      })
      .toBe(0);

    await expect
      .poll(async () => await helper.unreadBadgeTextForChapter(chapterTitle), { timeout: 5000 })
      .toBe(null);
    await expect(page.locator('.unread-divider')).toHaveCount(0);
  });

  test('Fully visible unread messages are auto-marked read on next interaction', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    const owner = await helper.registerAndLogin('autoread-owner');
    const sender = await helper.registerAndLogin('autoread-sender');

    const chapterTitle = `AutoRead Chapter ${Date.now()}`;
    const chapterId = await helper.createChapter(owner.sessionToken, chapterTitle, 'private');
    await helper.addChapterMember(owner.sessionToken, chapterId, sender.userId, 'editor');

    const messageA = await helper.createMessage(sender.sessionToken, `Unread A ${Date.now()}`);
    await helper.addMessageToChapter(sender.sessionToken, chapterId, messageA);
    const messageB = await helper.createMessage(sender.sessionToken, `Unread B ${Date.now()}`);
    await helper.addMessageToChapter(sender.sessionToken, chapterId, messageB);
    const messageC = await helper.createMessage(sender.sessionToken, `Unread C ${Date.now()}`);
    await helper.addMessageToChapter(sender.sessionToken, chapterId, messageC);

    await helper.navigateToApp();
    await helper.loginViaUI(owner.username, owner.password);
    await helper.navigateToMessaging();

    await helper.selectChapterByName(chapterTitle);
    await expect(page.locator('.status-line')).toContainText('Loaded', { timeout: 5000 });
    await expect(page.locator('.unread-divider')).toBeVisible({ timeout: 5000 });

    const beforeInteraction = await helper.chapterUnreadCount(owner.sessionToken, chapterId);
    expect(beforeInteraction.unreadCount).toBeGreaterThan(0);

    // Auto-read is interaction-driven. Click inside chat after messages were visible.
    await page.locator('.status-line').click();

    await expect
      .poll(async () => {
        const state = await helper.chapterUnreadCount(owner.sessionToken, chapterId);
        return state.unreadCount;
      })
      .toBe(0);
  });

  test('Unread-from barrier blocks auto-read until chapter switch', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    const owner = await helper.registerAndLogin('barrier-owner');
    const sender = await helper.registerAndLogin('barrier-sender');

    const chapterA = `Barrier A ${Date.now()}`;
    const chapterB = `Barrier B ${Date.now()}`;
    const chapterAId = await helper.createChapter(owner.sessionToken, chapterA, 'private');
    const chapterBId = await helper.createChapter(owner.sessionToken, chapterB, 'private');
    await helper.addChapterMember(owner.sessionToken, chapterAId, sender.userId, 'editor');

    const ownerAnchorMessageId = await helper.createMessage(owner.sessionToken, `Barrier anchor ${Date.now()}`);
    await helper.addMessageToChapter(owner.sessionToken, chapterAId, ownerAnchorMessageId);

    const ids: number[] = [ownerAnchorMessageId];
    for (let i = 0; i < 3; i += 1) {
      const messageId = await helper.createMessage(sender.sessionToken, `Barrier msg ${i + 1} ${Date.now()}`);
      await helper.addMessageToChapter(sender.sessionToken, chapterAId, messageId);
      ids.push(messageId);
    }

    await helper.navigateToApp();
    await helper.loginViaUI(owner.username, owner.password);
    await helper.navigateToMessaging();

    await helper.selectChapterByName(chapterA);
    await expect(page.locator('.status-line')).toContainText('Loaded 4 chapter messages', { timeout: 5000 });
    await expect(page.getByRole('button', { name: `#${ids[0]}` })).toBeVisible({ timeout: 8000 });

    // Select owner-authored anchor and set unread barrier from there to block it and all newer messages.
    await page.getByRole('button', { name: `#${ownerAnchorMessageId}` }).first().click();
    await page.getByRole('button', { name: 'Unread From Here' }).click();

    const afterUnreadFrom = await helper.chapterUnreadCount(owner.sessionToken, chapterAId);
    expect(afterUnreadFrom.unreadCount).toBeGreaterThan(0);

    // Interact repeatedly and even navigate away/back; barrier should still prevent auto-read.
    await page.locator('.status-line').click();
    await page.getByRole('button', { name: 'Chapters' }).click();
    await page.getByRole('button', { name: 'Messaging' }).click();
    await page.locator('.status-line').click();

    await expect
      .poll(async () => {
        const state = await helper.chapterUnreadCount(owner.sessionToken, chapterAId);
        return state.unreadCount;
      })
      .toBe(afterUnreadFrom.unreadCount);

    // Chapter switch should reset barrier. Switching back allows interaction-based auto-read.
    await helper.selectChapterByName(chapterB);
    await expect(page.locator('.status-line')).toContainText('Loaded 0 chapter messages', { timeout: 8000 });
    await helper.selectChapterByName(chapterA);
    await expect(page.locator('.status-line')).toContainText('Loaded 4 chapter messages', { timeout: 8000 });
    await page.locator('.status-line').click();

    await expect
      .poll(async () => {
        const state = await helper.chapterUnreadCount(owner.sessionToken, chapterAId);
        return state.unreadCount;
      })
      .toBe(0);

    // Keep chapterB referenced so test setup remains explicit and no-unused-vars is avoided in strict configs.
    expect(chapterBId).toBeGreaterThan(0);
  });

  test('Sending with visible unread marks old and newly sent message as read and keeps sent message visible', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    const owner = await helper.registerAndLogin('send-owner');
    const sender = await helper.registerAndLogin('send-sender');

    const chapterTitle = `Send In Unread ${Date.now()}`;
    const chapterId = await helper.createChapter(owner.sessionToken, chapterTitle, 'private');
    await helper.addChapterMember(owner.sessionToken, chapterId, sender.userId, 'editor');

    const firstUnread = await helper.createMessage(sender.sessionToken, `Send flow unread 1 ${Date.now()}`);
    await helper.addMessageToChapter(sender.sessionToken, chapterId, firstUnread);
    const secondUnread = await helper.createMessage(sender.sessionToken, `Send flow unread 2 ${Date.now()}`);
    await helper.addMessageToChapter(sender.sessionToken, chapterId, secondUnread);

    await helper.navigateToApp();
    await helper.loginViaUI(owner.username, owner.password);
    await helper.navigateToMessaging();

    await helper.selectChapterByName(chapterTitle);
    await expect(page.locator('.unread-divider')).toBeVisible({ timeout: 5000 });
    expect(await helper.unreadBadgeTextForChapter(chapterTitle)).toBe('2');

    const sentMessage = `Owner sent ${Date.now()}`;
    await helper.sendMessageViaUI(sentMessage);

    // Backend should include the new message immediately.
    await expect
      .poll(async () => {
        const messages = await helper.listChapterMessages(owner.sessionToken, chapterId, 50);
        return messages.some(m => m.content === sentMessage);
      })
      .toBe(true);

    await expect(page.locator('.message-content', { hasText: sentMessage })).toBeVisible({ timeout: 5000 });
    await expect(page.locator('.unread-divider')).toHaveCount(0);
    await expect
      .poll(async () => await helper.unreadBadgeTextForChapter(chapterTitle), { timeout: 5000 })
      .toBe(null);

    await expect
      .poll(async () => {
        const state = await helper.chapterUnreadCount(owner.sessionToken, chapterId);
        return state.unreadCount;
      })
      .toBe(0);
  });
});
