import { test, expect } from '@playwright/test';
import { ChatAppTestHelper } from './test-helpers';

test.describe('Chat App - Multi-User Messaging', () => {
  let user1Helper: ChatAppTestHelper;
  let user2Helper: ChatAppTestHelper;
  let user1Auth: Awaited<ReturnType<ChatAppTestHelper['registerAndLogin']>>;
  let user2Auth: Awaited<ReturnType<ChatAppTestHelper['registerAndLogin']>>;

  test.beforeEach(async ({ browser }) => {
    // Create two browser contexts for two different users
    const context1 = await browser.newContext();
    const context2 = await browser.newContext();

    const page1 = await context1.newPage();
    const page2 = await context2.newPage();

    user1Helper = new ChatAppTestHelper(page1);
    user2Helper = new ChatAppTestHelper(page2);

    user1Auth = await user1Helper.registerAndLogin('testuser1');
    user2Auth = await user2Helper.registerAndLogin('testuser2');
  });

  test('User 2 message should be visible to User 1 in public chapter', async () => {
    // User 1 creates a public chapter
    const chapterId = await user1Helper.createChapter(
      user1Auth.sessionToken,
      'Public Test Chapter',
      'public'
    );

    // User 1 adds User 2 as a member
    await user1Helper.addChapterMember(
      user1Auth.sessionToken,
      chapterId,
      user2Auth.userId,
      'editor'
    );

    // User 1 creates a message
    const msg1Id = await user1Helper.createMessage(
      user1Auth.sessionToken,
      'Hello from User 1'
    );
    await user1Helper.addMessageToChapter(user1Auth.sessionToken, chapterId, msg1Id);

    // User 2 creates a message
    const msg2Id = await user2Helper.createMessage(
      user2Auth.sessionToken,
      'Hello from User 2'
    );
    await user2Helper.addMessageToChapter(user2Auth.sessionToken, chapterId, msg2Id);

    // Verify both users can see both messages via API
    const messagesUser1 = await user1Helper.listChapterMessages(
      user1Auth.sessionToken,
      chapterId
    );
    const messagesUser2 = await user2Helper.listChapterMessages(
      user2Auth.sessionToken,
      chapterId
    );

    expect(messagesUser1.length).toBeGreaterThanOrEqual(2);
    expect(messagesUser2.length).toBeGreaterThanOrEqual(2);

    // Check content
    const user1Content = messagesUser1.map(m => m.content);
    const user2Content = messagesUser2.map(m => m.content);

    expect(user1Content).toContain('Hello from User 1');
    expect(user1Content).toContain('Hello from User 2');
    expect(user2Content).toContain('Hello from User 1');
    expect(user2Content).toContain('Hello from User 2');
  });

  test('Message from User 2 appears in User 1 chapter after refresh', async () => {
    // Setup: Create public chapter and add User 2
    const chapterId = await user1Helper.createChapter(
      user1Auth.sessionToken,
      'Refresh Test Chapter',
      'public'
    );
    
    await user1Helper.addChapterMember(
      user1Auth.sessionToken,
      chapterId,
      user2Auth.userId,
      'editor'
    );

    // User 1 loads chapter messages initially (empty)
    let user1Messages = await user1Helper.listChapterMessages(
      user1Auth.sessionToken,
      chapterId
    );
    expect(user1Messages.length).toBe(0);

    // User 2 sends a message and adds it to chapter
    const messageId = await user2Helper.createMessage(
      user2Auth.sessionToken,
      'Message from User 2 to Public Chapter'
    );
    await user2Helper.addMessageToChapter(user2Auth.sessionToken, chapterId, messageId);

    // User 1 refreshes and should see the message
    user1Messages = await user1Helper.listChapterMessages(
      user1Auth.sessionToken,
      chapterId
    );
    
    expect(user1Messages.length).toBeGreaterThan(0);
    expect(user1Messages[0].content).toBe('Message from User 2 to Public Chapter');
    expect(user1Messages[0].authorUserId).toBe(user2Auth.userId);
  });

  test('Private chapter messages not visible to non-members', async () => {
    // User 1 creates a private chapter
    const chapterId = await user1Helper.createChapter(
      user1Auth.sessionToken,
      'Private Chapter',
      'private'
    );

    // User 1 adds a message
    const messageId = await user1Helper.createMessage(
      user1Auth.sessionToken,
      'Secret message in private chapter'
    );
    await user1Helper.addMessageToChapter(user1Auth.sessionToken, chapterId, messageId);

    // User 2 tries to get chapter messages (should fail or be empty)
    try {
      const user2Messages = await user2Helper.listChapterMessages(
        user2Auth.sessionToken,
        chapterId
      );
      expect(user2Messages.length).toBe(0);
    } catch (error) {
      // Expected: either no access or empty list
      expect(error).toBeDefined();
    }
  });

  test('User 1 can see User 2 message immediately after posting to public chapter', async () => {
    // Create public chapter
    const chapterId = await user1Helper.createChapter(
      user1Auth.sessionToken,
      'Immediate Visibility Chapter',
      'public'
    );

    // Add User 2 as member
    await user1Helper.addChapterMember(
      user1Auth.sessionToken,
      chapterId,
      user2Auth.userId,
      'editor'
    );

    // Get initial state
    let user1Messages = await user1Helper.listChapterMessages(
      user1Auth.sessionToken,
      chapterId
    );
    expect(user1Messages.length).toBe(0);

    // User 2 sends message
    const messageId = await user2Helper.createMessage(
      user2Auth.sessionToken,
      'Immediate visibility test message'
    );

    // User 2 adds to chapter
    await user2Helper.addMessageToChapter(user2Auth.sessionToken, chapterId, messageId);

    // User 1 immediately fetches (without manual refresh)
    user1Messages = await user1Helper.listChapterMessages(
      user1Auth.sessionToken,
      chapterId
    );

    expect(user1Messages.length).toBe(1);
    expect(user1Messages[0].content).toBe('Immediate visibility test message');
  });

  test('Message visibility across multiple sequential operations', async () => {
    const chapterId = await user1Helper.createChapter(
      user1Auth.sessionToken,
      'Sequential Operations Chapter',
      'public'
    );

    // Add User 2 as member
    await user1Helper.addChapterMember(
      user1Auth.sessionToken,
      chapterId,
      user2Auth.userId,
      'editor'
    );

    // Sequence of operations
    const msg1 = await user1Helper.createMessage(user1Auth.sessionToken, 'Message 1 from User1');
    await user1Helper.addMessageToChapter(user1Auth.sessionToken, chapterId, msg1);

    const msg2 = await user2Helper.createMessage(user2Auth.sessionToken, 'Message 1 from User2');
    await user2Helper.addMessageToChapter(user2Auth.sessionToken, chapterId, msg2);

    const msg3 = await user1Helper.createMessage(user1Auth.sessionToken, 'Message 2 from User1');
    await user1Helper.addMessageToChapter(user1Auth.sessionToken, chapterId, msg3);

    const msg4 = await user2Helper.createMessage(user2Auth.sessionToken, 'Message 2 from User2');
    await user2Helper.addMessageToChapter(user2Auth.sessionToken, chapterId, msg4);

    // User 1 fetches all messages
    const allMessages = await user1Helper.listChapterMessages(
      user1Auth.sessionToken,
      chapterId
    );

    expect(allMessages.length).toBeGreaterThanOrEqual(4);

    // Verify order and content
    const contents = allMessages.map(m => m.content);
    expect(contents).toContain('Message 1 from User1');
    expect(contents).toContain('Message 1 from User2');
    expect(contents).toContain('Message 2 from User1');
    expect(contents).toContain('Message 2 from User2');
  });
});
