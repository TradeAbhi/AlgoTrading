import { useState } from 'react';
import { nseApi } from '../api';

function Nse52WeekHigh() {
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [selectedFile, setSelectedFile] = useState(null);

  const handleSendWeekHigh = async () => {
    setLoading(true);
    try {
      const result = await nseApi.sendWeekHigh();
      setMessage(`52-week high CSV sent to Telegram. Stocks found: ${result.stocksFound}`);
    } catch (error) {
      setMessage('Error sending week high: ' + error.message);
    }
    setLoading(false);
  };

  const handleSendWeekLow = async () => {
    setLoading(true);
    try {
      const result = await nseApi.sendWeekLow();
      setMessage(`52-week low CSV sent to Telegram. Stocks found: ${result.stocksFound}`);
    } catch (error) {
      setMessage('Error sending week low: ' + error.message);
    }
    setLoading(false);
  };

  const handleSendBoth = async () => {
    setLoading(true);
    try {
      await nseApi.sendBoth();
      setMessage('Both 52-week high and low CSVs sent to Telegram');
    } catch (error) {
      setMessage('Error sending both: ' + error.message);
    }
    setLoading(false);
  };

  const handleScanWeeklyCloseBreakout = async () => {
    setLoading(true);
    try {
      await nseApi.scanWeeklyCloseBreakout();
      setMessage('52-week high weekly close breakout scan completed');
    } catch (error) {
      setMessage('Error scanning weekly close breakout: ' + error.message);
    }
    setLoading(false);
  };

  const handleFileChange = (e) => {
    setSelectedFile(e.target.files[0]);
  };

  const handleUpload = async () => {
    if (!selectedFile) {
      setMessage('Please select a file first');
      return;
    }
    setLoading(true);
    try {
      const result = await nseApi.upload52Week(selectedFile);
      setMessage(`File uploaded successfully. Processing ${result.symbolsProcessed} symbols.`);
      setSelectedFile(null);
    } catch (error) {
      setMessage('Error uploading file: ' + error.message);
    }
    setLoading(false);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>🇮🇳 NSE 52-Week High/Low Scanner</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      {/* Control Panel */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Control Panel</h3>
        <div style={styles.buttonGroup}>
          <button 
            onClick={handleSendWeekHigh} 
            disabled={loading}
            style={styles.button}
          >
            📈 Send 52-Week High
          </button>
          <button 
            onClick={handleSendWeekLow} 
            disabled={loading}
            style={styles.button}
          >
            📉 Send 52-Week Low
          </button>
          <button 
            onClick={handleSendBoth} 
            disabled={loading}
            style={styles.button}
          >
            📊 Send Both
          </button>
          <button 
            onClick={handleScanWeeklyCloseBreakout} 
            disabled={loading}
            style={styles.button}
          >
            🔍 Scan Weekly Close Breakout
          </button>
        </div>
      </div>

      {/* Upload Section */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Upload CSV for Custom Analysis</h3>
        <div style={styles.uploadContainer}>
          <input
            type="file"
            accept=".csv"
            onChange={handleFileChange}
            disabled={loading}
            style={styles.fileInput}
          />
          <button 
            onClick={handleUpload} 
            disabled={loading || !selectedFile}
            style={styles.uploadButton}
          >
            📤 Upload & Process
          </button>
        </div>
        {selectedFile && (
          <div style={styles.fileInfo}>
            Selected: {selectedFile.name}
          </div>
        )}
        <div style={styles.infoText}>
          Upload a CSV file with symbols to run weekly close breakout analysis. 
          The file should be in the same format as NSE 52-week highs CSV.
        </div>
      </div>
    </div>
  );
}

const styles = {
  container: {
    padding: '20px',
    background: '#f8f9fa',
    borderRadius: '8px',
    marginBottom: '20px'
  },
  title: {
    color: '#333',
    marginBottom: '20px',
    fontSize: '22px'
  },
  message: {
    background: '#e3f2fd',
    border: '1px solid #2196f3',
    borderRadius: '8px',
    padding: '12px',
    marginBottom: '20px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    color: '#1976d2'
  },
  closeBtn: {
    background: 'none',
    border: 'none',
    fontSize: '20px',
    cursor: 'pointer',
    color: '#1976d2'
  },
  section: {
    marginBottom: '20px',
    padding: '15px',
    background: 'white',
    borderRadius: '8px',
    border: '1px solid #e0e0e0'
  },
  sectionTitle: {
    color: '#333',
    marginBottom: '10px',
    fontSize: '16px'
  },
  button: {
    padding: '10px 20px',
    background: '#28a745',
    color: 'white',
    border: 'none',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '13px',
    fontWeight: '600',
    transition: 'background 0.2s'
  },
  buttonGroup: {
    display: 'flex',
    gap: '10px',
    flexWrap: 'wrap'
  },
  uploadContainer: {
    display: 'flex',
    gap: '10px',
    alignItems: 'center',
    flexWrap: 'wrap'
  },
  fileInput: {
    padding: '8px',
    border: '1px solid #ccc',
    borderRadius: '6px',
    fontSize: '13px'
  },
  uploadButton: {
    padding: '10px 20px',
    background: '#667eea',
    color: 'white',
    border: 'none',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '13px',
    fontWeight: '600',
    transition: 'background 0.2s'
  },
  fileInfo: {
    marginTop: '10px',
    fontSize: '13px',
    color: '#666'
  },
  infoText: {
    marginTop: '15px',
    fontSize: '12px',
    color: '#888',
    lineHeight: '1.5'
  }
};

export default Nse52WeekHigh;
