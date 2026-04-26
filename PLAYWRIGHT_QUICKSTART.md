# Quick Start Guide - E2E Testing

This guide helps you run the Playwright E2E tests for the Chat Application.

## Prerequisites

1. **Node.js**: Install Node.js 16 or higher
2. **Backend**: The backend must be running on `http://localhost:8080`
3. **Database**: PostgreSQL must be initialized with migrations

## Setup Steps

### 1. Install Playwright Dependencies
```bash
cd chat-app
npm install
```

### 2. Start the Backend
In one terminal, start the backend:

```bash
sbt "backend/run"
```

Wait for it to show "Server is ready" message.

### 3. Build Frontend (Optional)
If needed, build the frontend:
```bash
sbt "frontend/fastLinkJS"
```

The frontend is served by the backend at `http://localhost:8080/`.

### 4. Run Tests

In another terminal:

```bash
# Run all tests
npm test

# Run tests in headed mode (see browser)
npm run test:headed

# Run specific test file
npx playwright test e2e/visibility-issue.spec.ts

# Run specific test
npx playwright test -g "should be visible"

# Debug mode
npm run test:debug

# View test results
npm run test:report
```

## Understanding Test Results

### Test Output
Each test will show:
- ✓ PASSED - test completed successfully
- ✗ FAILED - test did not pass
- ⊙ SKIPPED - test was skipped
- ⊘ TIMEOUT - test exceeded time limit

### Test Report
After tests run, view the HTML report:
```bash
npm run test:report
```

This opens a detailed report with screenshots, videos, and traces of failed tests.

## Key Test Scenarios

### 1. Multi-User Messaging (`multi-user-messaging.spec.ts`)
Tests that messages between multiple users work correctly:
- ✓ User 2 message visible to User 1 in public chapter (API level)
- ✓ Message appears after refresh
- ✓ Private chapters not accessible to non-members

### 2. UI Tests (`ui-tests.spec.ts`)
Tests frontend UI functionality:
- ✓ Login/logout
- ✓ Navigation between tabs
- ✓ Chapter creation
- ✓ Status messages

### 3. Visibility Issue (`visibility-issue.spec.ts`) ⚠️
**This is the test for the reported issue:**
Tests that message from User 2 appears in User 1's UI:
- ✓ API-level: Message is retrievable via API
- ✗ UI-level: Message may not auto-refresh in the UI (THE BUG)

### 4. Integration Tests (`integration-tests.spec.ts`)
Tests data consistency:
- ✓ Message data consistency across API calls
- ✓ Member additions are visible immediately
- ✓ Author information is preserved
- ✓ Visibility changes are persisted

## Understanding the Reported Issue

The tests in `visibility-issue.spec.ts` demonstrate the issue:

```
[PROBLEM]
User 1 and User 2 both have a public chapter.
User 2 sends a message to the chapter.
User 1 can fetch the message via API but doesn't see it in the UI.

[VERIFICATION]
The test does:
1. User 2 creates message and adds to chapter ✓
2. User 1 queries API and sees message ✓
3. User 1's UI doesn't show message ✗ (WITHOUT MANUAL REFRESH)
```

## Debugging Failed Tests

### Enable Video Recording
Videos are automatically recorded for failed tests in:
```
test-results/[test-name]/
```

### View Trace
Traces (detailed execution logs) are saved for each test:
1. Run tests
2. Open report: `npm run test:report`
3. Click on failed test to see trace

### Debug a Single Test
```bash
npm run test:debug -- -g "specific test name"
```

This opens Playwright Inspector where you can:
- Step through each action
- See DOM state at each step
- Inspect elements

## Common Issues

### "Connection refused"
```
Error: connect ECONNREFUSED 127.0.0.1:8080
```
**Solution**: Ensure backend is running and healthy
```bash
# Check if backend is running
curl http://localhost:8080/health

# If not, start it
sbt "backend/run"
```

### "Timed out waiting for element"
```
Error: Timeout waiting for selector '.workspace' (30000ms)
```
**Solution**: Backend may be slow or UI element missing
- Check browser screenshots in test report
- Verify frontend is being served correctly

### Tests pass in headed mode but fail in headless
**Solution**: This is usually timing-related
- Increase timeout in `playwright.config.ts`
- Add more `waitForTimeout()` calls in helpers

### "Request failed: 401 Unauthorized"
```
Error: Request failed: 401 Unauthorized
```
**Solution**: Session token issue
- Ensure login successful before making API calls
- Check test helper login logic

## Running Tests in CI/CD

For CI/CD pipelines (GitHub Actions, GitLab CI, etc.):

```yaml
# Example GitHub Actions
- name: Install dependencies
  run: npm install

- name: Run E2E tests
  run: npm test

- name: Upload reports
  if: always()
  uses: actions/upload-artifact@v2
  with:
    name: playwright-report
    path: playwright-report/
```

Key points:
- Tests run in headless mode by default
- Set `CI=true` environment variable
- Ensure backend is accessible
- Keep test timeout reasonable

## What's Being Tested

| Feature | Tested | Status |
|---------|--------|--------|
| User Registration | Yes | ✓ |
| Login/Logout | Yes | ✓ |
| Create Chapter | Yes | ✓ |
| Chapter Visibility | Yes | ✓ |
| Add Members | Yes | ✓ |
| Create Message | Yes | ✓ |
| Add Message to Chapter | Yes | ✓ |
| Real-time UI Updates | Yes | ✗ Issue |
| Private Chapter Access | Yes | ✓ |

## Next Steps

1. **Run a quick test**: `npm run test:headed`
2. **Check the issue**: `npx playwright test e2e/visibility-issue.spec.ts`
3. **Read the report**: `npm run test:report`
4. **Review recommendations**: See `e2e/README.md` for fix suggestions

## Need Help?

1. Check test output and error messages
2. View HTML report with `npm run test:report`
3. Check Playwright documentation: https://playwright.dev/
4. Review test file comments for details on what each test does

---

**Note**: The main issue being tested is that messages from other users don't auto-refresh in the UI. This is working at the API level but the frontend needs real-time updates or polling to show messages as they arrive.
