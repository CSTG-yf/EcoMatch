module.exports = {
  rootDir: '.',
  testEnvironment: 'jsdom',
  testMatch: [
    '<rootDir>/src/pages/Dashboard/*.test.ts',
    '<rootDir>/src/pages/ExportCenter/*.test.ts',
    '<rootDir>/src/pages/ControlledShare/*.test.ts',
  ],
  transform: {
    '^.+\\.(js|jsx|mjs|cjs|ts|tsx)$': '<rootDir>/../chat-sdk/config/jest/babelTransform.js',
  },
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
    '\\.(css|less)$': '<rootDir>/src/pages/ControlledShare/styleMock.js',
  },
  moduleFileExtensions: ['js', 'ts', 'tsx', 'json'],
};
