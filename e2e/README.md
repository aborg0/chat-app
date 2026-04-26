# Chat App E2E Tests

This directory contains end-to-end tests for the Chat Application using Playwright.

## Overview

The test suite covers:
- **Multi-user messaging**: Tests that messages are correctly shared between users in public chapters
- **UI interactions**: Tests for login, navigation, chapter creation, and message sending
- **Visibility issues**: Tests that specifically target the reported issue where messages from other users aren't visible on the original user's UI
- **Access control**: Tests that verify private chapters are not accessible to non-members

## Setup

### Prerequisites
- Node.js 16+ installed
- Backend server running on `http://localhost:8080`
- PostgreSQL database configured and running

### Installation

```bash
npm install
```

### Running Tests

#### Run all tests
```bash
npm test
```

#### Run tests in headed mode (visible browser)
```bash
npm run test:headed
```

#### Run tests with UI
```bash
npm run test:ui
```

#### Debug tests
```bash
npm run test:debug
```

#### View test report
```bash
npm run test:report
```

#### Run specific test file
```bash
npx playwright test e2e/multi-user-messaging.spec.ts
```

#### Run specific test by name
```bash
npx playwright test -g "message should be visible"
```

## Test Files

### `test-helpers.ts`
Helper class for common test operations:
- User registration and login
- Navigation
- API calls (messages, chapters, members)
- UI interactions (sending messages, selecting chapters)

### `multi-user-messaging.spec.ts`
Tests for multi-user scenarios:
- User 2's message visibility in User 1's public chapter
- Message refresh behavior
- Private chapter access control
- Sequential message ordering

### `ui-tests.spec.ts`
Frontend UI tests:
- Login and navigation
- Chapter creation via UI
- Visibility toggle
- Status message display
- Logout functionality

### `visibility-issue.spec.ts`
**IMPORTANT**: Tests that specifically reproduce the reported issue
- User 2's message not appearing in User 1's UI in real-time
- Message visibility after manual refresh
- Access control verification
- Message ordering with multiple users

## The Issue

### Reported Problem
"When another user sends a message to a public chapter, it is not visible on the original user's UI."

### Root Cause Analysis
The frontend (`ChatView.scala`) loads chapter messages once when a chapter is selected. The UI does not:
1. Auto-refresh when new messages are added by other users
2. Have WebSocket/real-time subscriptions
3. Have polling mechanism to check for new messages

### Current Behavior
- **API Level**: Messages are correctly stored and retrieved from the database
- **UI Level**: Messages don't appear until the user manually refreshes or navigates away and back to the chapter

### Evidence
The test `ISSUE: User2 message should appear in User1 UI after posting to public chapter` in `visibility-issue.spec.ts` demonstrates:
1. User1 can see User2's message via API (`listChapterMessages`) ✓
2. But User1 might not see it in the UI without manual refresh ✗

## Recommended Fixes

### Short-term Fix (Polling)
Add periodic polling in `ChatView.scala`:
```scala
def loadChapterTimeline(resetCursor: Boolean): Unit = {
  // ... existing code ...
}

// Add auto-refresh every 3 seconds
onMountCallback { _ =>
  val intervalId = js.timers.setInterval(() => {
    if selectedChapterIdVar.now().isDefined then {
      loadChapterTimeline(resetCursor = false)
    }
  }, 3000)
  onUnmountCallback(_ => js.timers.clearInterval(intervalId))
}
```

### Long-term Fix (WebSocket)
Implement WebSocket subscription for real-time updates:
1. Add WebSocket support to the backend
2. Create a subscription API for chapter messages
3. Update frontend to connect to WebSocket on chapter selection
4. Push new messages to all connected subscribers

## Test Configuration

The tests are configured in `playwright.config.ts`:
- **baseURL**: `http://localhost:8080`
- **Browsers**: Chromium, Firefox, WebKit
- **Timeout**: 30 seconds per test
- **Screenshots**: Captured on failure
- **Videos**: Recorded on failure
- **Reports**: HTML report in `playwright-report/`

## Running Tests Against Different Environments

To run tests against a different backend URL, set an environment variable or modify the test:

```bash
# Run tests with custom backend URL
BACKEND_URL=http://localhost:9000 npm test
```

Or modify the test:
```typescript
const helper = new ChatAppTestHelper(page, 'http://custom-url:8080');
```

## CI/CD Integration

For CI/CD pipelines, ensure:
1. Backend is running and healthy
2. Database is initialized and clean
3. Run tests in headless mode (default)
4. Collect reports for analysis

Example GitHub Actions workflow:
```yaml
- name: Install dependencies
  run: npm install

- name: Run E2E tests
  run: npm test

- name: Upload test report
  if: always()
  uses: actions/upload-artifact@v2
  with:
    name: playwright-report
    path: playwright-report/
```

## Troubleshooting

### Tests fail with "Connection refused"
- Ensure backend is running on `http://localhost:8080`
- Check database connection
- Run migrations if needed

### Tests timeout
- Increase timeout in `playwright.config.ts`
- Check network connectivity
- Look for performance issues in backend

### Messages not appearing in UI tests
- This is expected and is the reported issue
- The API-level tests should pass
- See the fix recommendations above

## Test Coverage

Current coverage:
- ✓ User registration and authentication
- ✓ Multi-user message creation
- ✓ Chapter management (create, delete, visibility)
- ✓ Member management
- ✓ Message visibility via API
- ✓ Access control
- ✗ Real-time UI updates (this is the issue being tested)

## Future Enhancements

1. Add visual regression testing
2. Add performance benchmarks
3. Add load testing for concurrent users
4. Add accessibility testing
5. Add mobile browser testing

## Contact

For issues or questions about the tests, check the test output or the implementation in the test files.
