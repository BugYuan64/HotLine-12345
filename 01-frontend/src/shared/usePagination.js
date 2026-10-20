/**
 * 列表分页组合式函数：page/pageSize/total + change/reset 处理（配合 el-pagination 与各列表 load）
 * 用法：
 *   const { page, pageSize, total, onPageChange, onSizeChange, reset } = usePagination(load)
 *   el-pagination :current-page="page" :page-size="pageSize" :total="total"
 *     @current-change="onPageChange" @size-change="onSizeChange"
 */
import { ref } from 'vue'

export function usePagination(loadFn, defaultPageSize = 20) {
  const page = ref(1)
  const pageSize = ref(defaultPageSize)
  const total = ref(0)

  const onPageChange = (p) => {
    page.value = p
    loadFn(page.value)
  }
  const onSizeChange = (s) => {
    pageSize.value = s
    page.value = 1
    loadFn(1)
  }
  const setTotal = (t) => { total.value = t || 0 }
  const reset = () => { page.value = 1 }

  return { page, pageSize, total, setTotal, onPageChange, onSizeChange, reset }
}
