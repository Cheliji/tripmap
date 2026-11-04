import pluginVue from 'eslint-plugin-vue'

// ESLint 9 flat 配置：启用 vue3 推荐规则集
export default [
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.vue', '**/*.js'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module'
    },
    rules: {
      // 项目自定义规则后续在此扩展
    }
  },
  {
    ignores: ['dist/**', 'node_modules/**']
  }
]
