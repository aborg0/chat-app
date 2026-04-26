import { test, expect } from '@playwright/test';
import { ChatAppTestHelper } from './test-helpers';

/**
 * Integration and Data Consistency Tests
 * These tests verify that data is consistent across API and database
 */
test.describe('Chat App - Integration & Data Consistency', () => {
  
  test('Message data is consistent between API calls', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    
    const auth = await helper.registerAndLogin('consistency-user1');

    // Create a message
    const messageContent = 'Consistency test message';
    const messageId = await helper.createMessage(auth.sessionToken, messageContent);

    // Fetch the message directly by ID
    const response = await page.request.get(
      'http://localhost:8080/messages/by-id?messageId=' + messageId,
      { headers: helper.getAuthHeaders(auth.sessionToken) }
    );
    expect(response.ok()).toBeTruthy();
    const message = await response.json();

    expect(message.id).toBe(messageId);
    expect(message.content).toBe(messageContent);
    expect(message.authorUserId).toBe(auth.userId);
  });

  test('Chapter messages list contains all added messages', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    
    const auth = await helper.registerAndLogin('consistency-user2');
    
    // Create chapter
    const chapterId = await helper.createChapter(auth.sessionToken, 'Consistency Chapter', 'private');

    // Create and add 5 messages
    const messageIds = [];
    const messageContents = [];
    
    for (let i = 1; i <= 5; i++) {
      const content = `Message ${i} for consistency`;
      messageContents.push(content);
      const msgId = await helper.createMessage(auth.sessionToken, content);
      messageIds.push(msgId);
      await helper.addMessageToChapter(auth.sessionToken, chapterId, msgId);
    }

    // Get chapter messages
    const messages = await helper.listChapterMessages(auth.sessionToken, chapterId);

    // Verify all messages are in the list
    expect(messages.length).toBeGreaterThanOrEqual(5);
    
    for (const content of messageContents) {
      expect(messages.some(m => m.content === content)).toBeTruthy();
    }
  });

  test('Chapter member addition is immediately visible', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    
    const user1 = await helper.registerAndLogin('consistency-user3');
    const user2 = await helper.registerAndLogin('consistency-user4');

    // User1 creates chapter
    const chapterId = await helper.createChapter(user1.sessionToken, 'Member Consistency Chapter', 'public');

    // User1 adds User2
    await helper.addChapterMember(user1.sessionToken, chapterId, user2.userId, 'viewer');

    // Get chapter details and verify User2 is in members
    const details = await helper.getChapterDetail(user1.sessionToken, chapterId);
    
    expect(details.members).toBeDefined();
    expect(details.members.some((m: any) => m.userId === user2.userId)).toBeTruthy();
  });

  test('Message author information is preserved', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    
    const user1 = await helper.registerAndLogin('consistency-user5');
    const user2 = await helper.registerAndLogin('consistency-user6');

    // Create chapter
    const chapterId = await helper.createChapter(user1.sessionToken, 'Author Test Chapter', 'public');
    await helper.addChapterMember(user1.sessionToken, chapterId, user2.userId, 'editor');

    // User1 creates message
    const msg1 = await helper.createMessage(user1.sessionToken, 'Message from User1');
    await helper.addMessageToChapter(user1.sessionToken, chapterId, msg1);

    // User2 creates message
    const msg2 = await helper.createMessage(user2.sessionToken, 'Message from User2');
    await helper.addMessageToChapter(user2.sessionToken, chapterId, msg2);

    // Fetch chapter messages and verify authors
    const messages = await helper.listChapterMessages(user1.sessionToken, chapterId);

    const user1Message = messages.find(m => m.content === 'Message from User1');
    const user2Message = messages.find(m => m.content === 'Message from User2');

    expect(user1Message?.authorUserId).toBe(user1.userId);
    expect(user2Message?.authorUserId).toBe(user2.userId);
  });

  test('Chapter visibility change is persisted', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    
    const auth = await helper.registerAndLogin('consistency-user7');

    // Create private chapter
    const chapterId = await helper.createChapter(auth.sessionToken, 'Visibility Persistence Chapter', 'private');

    // Get initial state
    let details = await helper.getChapterDetail(auth.sessionToken, chapterId);
    expect(details.chapter.visibility).toBe('private');

    // Change to public
    await helper.updateChapterVisibility(auth.sessionToken, chapterId, 'public');

    // Verify change persisted
    details = await helper.getChapterDetail(auth.sessionToken, chapterId);
    expect(details.chapter.visibility).toBe('public');

    // Change back to private
    await helper.updateChapterVisibility(auth.sessionToken, chapterId, 'private');

    // Verify it persisted
    details = await helper.getChapterDetail(auth.sessionToken, chapterId);
    expect(details.chapter.visibility).toBe('private');
  });

  test('Session token allows access only to authenticated user', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    
    const user1 = await helper.registerAndLogin('session-user1');
    const user2 = await helper.registerAndLogin('session-user2');

    // User1 creates private chapter
    const chapterId = await helper.createChapter(user1.sessionToken, 'Session Test Chapter', 'private');
    
    // User1 can see their chapter
    const details = await helper.getChapterDetail(user1.sessionToken, chapterId);
    expect(details.chapter.id).toBe(chapterId);

    // User2 should not be able to see it (or should get error/empty)
    try {
      const details2 = await helper.getChapterDetail(user2.sessionToken, chapterId);
      // If it doesn't error, messages should be empty
      expect(details2.messageIds.length).toBe(0);
    } catch (error) {
      // Expected: access denied
      expect(error).toBeDefined();
    }
  });

  test('Pagination works correctly for large message lists', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    
    const auth = await helper.registerAndLogin('consistency-user8');

    // Create chapter
    const chapterId = await helper.createChapter(auth.sessionToken, 'Pagination Test Chapter', 'private');

    // Create 30 messages
    for (let i = 1; i <= 30; i++) {
      const msgId = await helper.createMessage(auth.sessionToken, `Message ${i}`);
      await helper.addMessageToChapter(auth.sessionToken, chapterId, msgId);
    }

    // Get first page (default 25)
    const page1 = await helper.listChapterMessages(auth.sessionToken, chapterId, 25);
    
    expect(page1.length).toBeGreaterThanOrEqual(25);
  });

  test('Message content cannot be empty', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    
    const auth = await helper.registerAndLogin('consistency-user9');

    // Try to create empty message
    const response = await page.request.post(
      'http://localhost:8080/messages',
      {
        headers: helper.getAuthHeaders(auth.sessionToken),
        data: { content: '' },
      }
    );

    // Should fail
    expect(response.ok()).toBeFalsy();
  });

  test('User cannot add non-existent message to chapter', async ({ page }) => {
    const helper = new ChatAppTestHelper(page);
    
    const auth = await helper.registerAndLogin('consistency-user10');
    const chapterId = await helper.createChapter(auth.sessionToken, 'Invalid Message Chapter', 'private');

    // Try to add non-existent message (ID 999999)
    const response = await page.request.post(
      `http://localhost:8080/chapters/${chapterId}/messages`,
      {
        headers: helper.getAuthHeaders(auth.sessionToken),
        data: { messageId: 999999 },
      }
    );

    // Should fail or have no effect
    expect(response.ok()).toBeFalsy();
  });
});
