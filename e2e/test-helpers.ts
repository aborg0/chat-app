import { Page, expect } from '@playwright/test';
import type { Locator } from '@playwright/test';

export interface UserCredentials {
  username: string;
  password: string;
}

export interface AuthResponse {
  userId: number;
  sessionToken: string;
}

export interface RegisteredUser extends AuthResponse {
  username: string;
  password: string;
}

export interface ChapterUnreadState {
  chapterId: number;
  unreadCount: number;
  muteLevel: string;
}

export function uniqueUsername(prefix: string): string {
  const randomPart = Math.random().toString(36).slice(2, 8);
  return `${prefix}-${Date.now()}-${randomPart}`;
}

/**
 * Test utilities for the Chat Application E2E tests
 */
export class ChatAppTestHelper {
  private apiBaseUrl: string;
  private appBaseUrl: string;
  private page: Page;

  constructor(
    page: Page,
    apiBaseUrl: string = process.env.PLAYWRIGHT_API_URL ?? 'http://localhost:8080',
    appBaseUrl: string = process.env.PLAYWRIGHT_APP_URL ?? 'http://localhost:8081'
  ) {
    this.page = page;
    this.apiBaseUrl = apiBaseUrl;
    this.appBaseUrl = appBaseUrl;
  }

  async registerAndLogin(prefix: string, password: string = 'password123'): Promise<RegisteredUser> {
    const username = uniqueUsername(prefix);
    await this.registerUser({ username, password });
    const auth = await this.loginUser({ username, password });
    return {
      ...auth,
      username,
      password,
    };
  }

  /**
   * Register a new user with the given credentials
   */
  async registerUser(credentials: UserCredentials): Promise<AuthResponse> {
    const response = await this.page.request.post(`${this.apiBaseUrl}/auth/register`, {
      data: {
        username: credentials.username,
        password: credentials.password,
      },
    });
    expect(response.ok()).toBeTruthy();
    const data = await response.json();
    return { userId: data.userId, sessionToken: '' };
  }

  /**
   * Login a user and get session token
   */
  async loginUser(credentials: UserCredentials): Promise<AuthResponse> {
    const deviceId = `test-device-${Date.now()}`;
    const response = await this.page.request.post(`${this.apiBaseUrl}/auth/login`, {
      data: {
        username: credentials.username,
        password: credentials.password,
        deviceId: deviceId,
      },
    });
    expect(response.ok()).toBeTruthy();
    const data = await response.json();
    return { userId: data.userId, sessionToken: data.sessionToken };
  }

  /**
   * Navigate to the app and wait for it to load
   */
  async navigateToApp(): Promise<void> {
    await this.page.goto(this.appBaseUrl, { waitUntil: 'domcontentloaded' });
    await expect(this.page.getByPlaceholder('Username')).toBeVisible({ timeout: 10000 });
  }

  /**
   * Perform login via UI
   */
  async loginViaUI(username: string, password: string): Promise<void> {
    await this.page.fill('input[placeholder="Username"]', username);
    await this.page.fill('input[placeholder="Password"]', password);
    await this.page.click('button:has-text("Login")');
    await this.page.waitForSelector('.workspace', { timeout: 5000 });
  }

  /**
   * Perform registration via UI
   */
  async registerViaUI(username: string, password: string): Promise<void> {
    await this.page.fill('input[placeholder="Username"]', username);
    await this.page.fill('input[placeholder="Password"]', password);
    await this.page.click('button:has-text("Register")');
    await this.page.waitForTimeout(1000); // Wait for registration message
  }

  /**
   * Get API authentication headers
   */
  getAuthHeaders(sessionToken: string): Record<string, string> {
    return {
      'X-Session-Token': sessionToken,
      'Content-Type': 'application/json',
    };
  }

  /**
   * Create a chapter via API
   */
  async createChapter(
    sessionToken: string,
    title: string,
    visibility: string = 'private',
    parentChapterId?: number
  ): Promise<number> {
    const response = await this.page.request.post(`${this.apiBaseUrl}/chapters`, {
      headers: this.getAuthHeaders(sessionToken),
      data: {
        title,
        parentChapterId: parentChapterId || null,
      },
    });
    expect(response.ok()).toBeTruthy();
    const data = await response.json();
    
    // Set visibility if needed
    if (visibility !== 'private') {
      await this.updateChapterVisibility(sessionToken, data.id, visibility);
    }
    
    return data.id;
  }

  /**
   * Update chapter visibility
   */
  async updateChapterVisibility(
    sessionToken: string,
    chapterId: number,
    visibility: string
  ): Promise<void> {
    const response = await this.page.request.put(
      `${this.apiBaseUrl}/chapters/${chapterId}/visibility`,
      {
        headers: this.getAuthHeaders(sessionToken),
        data: { visibility },
      }
    );
    expect(response.ok()).toBeTruthy();
  }

  /**
   * Create a message via API
   */
  async createMessage(sessionToken: string, content: string): Promise<number> {
    const response = await this.page.request.post(`${this.apiBaseUrl}/messages`, {
      headers: this.getAuthHeaders(sessionToken),
      data: { content },
    });
    expect(response.ok()).toBeTruthy();
    const data = await response.json();
    return data.id;
  }

  /**
   * Add a message to a chapter via API
   */
  async addMessageToChapter(
    sessionToken: string,
    chapterId: number,
    messageId: number
  ): Promise<void> {
    const response = await this.page.request.post(
      `${this.apiBaseUrl}/chapters/${chapterId}/messages`,
      {
        headers: this.getAuthHeaders(sessionToken),
        data: { messageId },
      }
    );
    expect(response.ok()).toBeTruthy();
  }

  /**
   * List chapter messages via API
   */
  async listChapterMessages(
    sessionToken: string,
    chapterId: number,
    pageSize: number = 25
  ): Promise<Array<{ id: number; content: string; authorUserId: number; version: number; clientEditedAtEpochMillis?: number | null }>> {
    const response = await this.page.request.get(
      `${this.apiBaseUrl}/chapters/${chapterId}/messages?pageSize=${pageSize}`,
      {
        headers: this.getAuthHeaders(sessionToken),
      }
    );
    expect(response.ok()).toBeTruthy();
    const data = await response.json();
    return data.items || [];
  }

  /**
   * Get one message by id via API
   */
  async getMessageById(
    sessionToken: string,
    messageId: number
  ): Promise<{ id: number; content: string; authorUserId: number; version: number; clientEditedAtEpochMillis?: number | null }> {
    const response = await this.page.request.get(
      `${this.apiBaseUrl}/messages/by-id?messageId=${messageId}`,
      {
        headers: this.getAuthHeaders(sessionToken),
      }
    );
    expect(response.ok()).toBeTruthy();
    return await response.json();
  }

  /**
   * Get chapter unread count via API
   */
  async chapterUnreadCount(sessionToken: string, chapterId: number): Promise<ChapterUnreadState> {
    const response = await this.page.request.get(
      `${this.apiBaseUrl}/chapters/${chapterId}/unread-count`,
      {
        headers: this.getAuthHeaders(sessionToken),
      }
    );
    expect(response.ok()).toBeTruthy();
    return await response.json();
  }

  /**
   * Mark a chapter message and newer as unread
   */
  async markUnreadFrom(
    sessionToken: string,
    chapterId: number,
    messageId: number
  ): Promise<void> {
    const response = await this.page.request.post(
      `${this.apiBaseUrl}/chapters/${chapterId}/messages/${messageId}/unread-from`,
      {
        headers: this.getAuthHeaders(sessionToken),
      }
    );
    expect(response.ok()).toBeTruthy();
  }

  /**
   * Navigate to Messaging tab via UI
   */
  async navigateToMessaging(): Promise<void> {
    await this.page.click('button:has-text("Messaging")');
    await this.page.waitForTimeout(500);
  }

  /**
   * Navigate to Chapters tab via UI
   */
  async navigateToChapters(): Promise<void> {
    await this.page.click('button:has-text("Chapters")');
    await this.page.waitForSelector('[class*="management-layout"]', { timeout: 5000 });
  }

  /**
   * Select a chapter in the UI
   */
  async selectChapterByName(chapterTitle: string): Promise<void> {
    const chapterButton = this.page.locator(`button:has-text("${chapterTitle}")`).first();
    await chapterButton.click();
    await this.page.waitForTimeout(500);
  }

  /**
   * Get chapter list item locator by exact chapter title text.
   */
  chapterItemByName(chapterTitle: string): Locator {
    return this.page
      .locator('.channel-item', {
        has: this.page.locator('.channel-title', { hasText: `# ${chapterTitle}` }),
      })
      .first();
  }

  /**
   * Read unread badge text for a chapter, if present.
   */
  async unreadBadgeTextForChapter(chapterTitle: string): Promise<string | null> {
    const badge = this.chapterItemByName(chapterTitle).locator('.channel-unread');
    if (await badge.count() > 0) {
      return (await badge.textContent())?.trim() ?? null;
    }
    return null;
  }

  /**
   * Send a message in the UI
   */
  async sendMessageViaUI(content: string): Promise<void> {
    const messageInput = this.page.locator('.composer-row .composer-input').first();
    await messageInput.fill(content);
    await this.page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(this.page.locator('.status-line')).toContainText('sent', { timeout: 8000 });
  }

  /**
   * Check if a message is visible in the UI
   */
  async isMessageVisible(content: string): Promise<boolean> {
    const messageElement = this.page.locator(`text="${content}"`);
    return await messageElement.isVisible().catch(() => false);
  }

  /**
   * Wait for message to appear in UI
   */
  async waitForMessageInUI(content: string, timeout: number = 5000): Promise<void> {
    await this.page.locator(`text="${content}"`).waitFor({ state: 'visible', timeout });
  }

  /**
   * Get all visible messages in the current view
   */
  async getVisibleMessages(): Promise<string[]> {
    // Try to find message content in various possible selectors
    const messageElements = await this.page.locator('[class*="message"], div:has-text(?)').all();
    const messages: string[] = [];
    
    for (const element of messageElements) {
      const text = await element.textContent();
      if (text && text.trim().length > 0) {
        messages.push(text.trim());
      }
    }
    
    return messages;
  }

  /**
   * Logout via UI
   */
  async logoutViaUI(): Promise<void> {
    await this.page.click('button:has-text("Log Out")');
    await this.page.waitForSelector('.login-screen', { timeout: 5000 });
  }

  /**
   * Add a member to a chapter via API
   */
  async addChapterMember(
    sessionToken: string,
    chapterId: number,
    userId: number,
    role: string = 'viewer'
  ): Promise<void> {
    const response = await this.page.request.post(
      `${this.apiBaseUrl}/chapters/${chapterId}/members`,
      {
        headers: this.getAuthHeaders(sessionToken),
        data: { userId, role },
      }
    );
    expect(response.ok()).toBeTruthy();
  }

  /**
   * Get chapter details via API
   */
  async getChapterDetail(sessionToken: string, chapterId: number): Promise<any> {
    const response = await this.page.request.get(
      `${this.apiBaseUrl}/chapters/${chapterId}`,
      {
        headers: this.getAuthHeaders(sessionToken),
      }
    );
    expect(response.ok()).toBeTruthy();
    return await response.json();
  }
}
