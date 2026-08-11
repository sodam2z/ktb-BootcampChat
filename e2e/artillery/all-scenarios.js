const {
  loginScenario,
  failedLoginScenario,
} = require("./scenarios/auth.scenario.js");
const {
  chatRoomCreationScenario,
  massMessageScenario,
  fileUploadScenario,
  forbiddenWordScenario,
} = require("./scenarios/chat.scenario.js");
const {
  fullProfileUpdateScenario,
} = require("./scenarios/profile.scenario.js");

function generateUserSchema() {
  const timestamp = Date.now();
  const randomId = Math.random().toString(36).substring(2, 8);

  // 고유한 테스트 사용자 생성
  const testUser = {
    email: `loadtest_${timestamp}_${randomId}@example.com`,
    password: "Password123!",
    passwordConfirm: "Password123!",
    name: `Load Test User ${randomId}`,
  };
  return testUser;
}

const allScenariosFlat = [
  // auth Scenarios
  failedLoginScenario,
  loginScenario,

  // chat Scenarios
  chatRoomCreationScenario,
  massMessageScenario,
  fileUploadScenario,
  forbiddenWordScenario,

  // profile Scenarios
  fullProfileUpdateScenario,
];

/**
 * 통합 시나리오 순차 실행
 * 모든 개별 시나리오를 순서대로 실행
 *
 * 각 단계를 try/catch로 감싸 에러 메시지 앞에 실패한 시나리오 이름을 붙여
 * 다시 던진다 — Artillery는 uncaught error를 "errors.<메시지>" 카운터로
 * 자동 집계하는데, Playwright 자체 에러 메시지(예: "locator.click: Timeout
 * 30000ms exceeded.")만으로는 여러 시나리오가 같은 종류의 액션을 쓰는 경우
 * 부하테스트 리포트에서 어느 시나리오가 실패했는지 구분할 수 없었다. 실제
 * 에러 타입/스택/재시도 동작은 그대로 두고 메시지 텍스트만 태그를 붙인다.
 */
async function allScenarios(page, vuContext) {
  const testUser = generateUserSchema();
  vuContext.vars.testUser = testUser;

  for (const scenario of allScenariosFlat) {
    try {
      await scenario(page, vuContext);
    } catch (err) {
      err.message = `[${scenario.name}] ${err.message}`;
      throw err;
    }
  }
}

async function profileScenario(page, vuContext) {
  const testUser = generateUserSchema();
  vuContext.vars.testUser = testUser;

  await loginScenario(page, vuContext);
  await fullProfileUpdateScenario(page, vuContext);
}

module.exports = {
  allScenarios,
  profileScenario,
};
