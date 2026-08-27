package me.kavishdevar.librepods.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextField

@Composable
fun RenameAirPodsDialog(
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf(TextFieldValue(currentName)) }
    
    Text("Rename AirPods", style = MaterialTheme.typography.titleMedium)
    
    TextField(
        value = newName,
        onValueChange = { newName = it },
        label = { Text("New name") },
        modifier = Modifier.fillMaxWidth().padding(8.dp)
    )
    
    Button(
        onClick = {
            if (newName.text.isNotBlank()) {
                onRename(newName.text.trim())
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Save")
    }
    
    Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Cancel")
    }
}