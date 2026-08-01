module.exports = {
  rootDir: '.',
  testEnvironment: 'jsdom',
  testMatch: ['<rootDir>/*.test.ts'],
  transform: {
    '^.+\\.(js|jsx|mjs|cjs|ts|tsx)$':
      '<rootDir>/../../../../chat-sdk/config/jest/babelTransform.js',
  },
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/../../../src/$1',
    '\\.(css|less)$': '<rootDir>/styleMock.js',
  },
  moduleFileExtensions: ['js', 'ts', 'tsx', 'json'],
};
