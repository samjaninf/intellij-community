// "Import extension function 'A.ext'" "true"
// ERROR: Unresolved reference: ext

import dep.TTA
import dep.ext

fun use(taa: TTA) {
    taa.ext<caret>()
}