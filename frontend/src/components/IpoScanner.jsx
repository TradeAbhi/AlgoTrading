import { useState, useEffect } from 'react';
import { ipoApi, nseApi } from '../api';

function IpoScanner() {
  const [allIpos, setAllIpos] = useState([]);
  const [upcomingIpos, setUpcomingIpos] = useState([]);
  const [activeTab, setActiveTab] = useState('upcoming');
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [csvFile, setCsvFile] = useState(null);
  const [nse52WeekFile, setNse52WeekFile] = useState(null);

  const loadData = async () => {
    try {
      const [allData, upcomingData] = await Promise.all([
        ipoApi.getAll(),
        ipoApi.getUpcoming()
      ]);
      setAllIpos(allData);
      setUpcomingIpos(upcomingData);
    } catch (error) {
      setMessage('Error loading data: ' + error.message);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleSync = async () => {
    setLoading(true);
    try {
      await ipoApi.sync();
      setMessage('IPO sync completed');
      await loadData();
    } catch (error) {
      setMessage('Error syncing: ' + error.message);
    }
    setLoading(false);
  };

  const handleCsvUpload = async () => {
    if (!csvFile) {
      setMessage('Please select a CSV file');
      return;
    }
    
    setLoading(true);
    try {
      const result = await ipoApi.uploadCsv(csvFile);
      setMessage(`Imported ${result.imported} IPOs (${result.errors} errors)`);
      setCsvFile(null);
      await loadData();
    } catch (error) {
      setMessage('Error uploading CSV: ' + error.message);
    }
    setLoading(false);
  };

  const handleListingOpenAlert = async () => {
    setLoading(true);
    try {
      await ipoApi.listingOpenAlert();
      setMessage('Listing open alert sent');
    } catch (error) {
      setMessage('Error sending alert: ' + error.message);
    }
    setLoading(false);
  };

  const handleListingEodAlert = async () => {
    setLoading(true);
    try {
      await ipoApi.listingEodAlert();
      setMessage('Listing EOD alert sent');
    } catch (error) {
      setMessage('Error sending alert: ' + error.message);
    }
    setLoading(false);
  };

  const handleUpcomingSummary = async () => {
    setLoading(true);
    try {
      await ipoApi.upcomingSummary();
      setMessage('Upcoming summary sent');
    } catch (error) {
      setMessage('Error sending summary: ' + error.message);
    }
    setLoading(false);
  };

  const handleStrategyScan = async () => {
    setLoading(true);
    try {
      await ipoApi.strategyScan();
      setMessage('IPO strategy scan completed');
    } catch (error) {
      setMessage('Error scanning: ' + error.message);
    }
    setLoading(false);
  };

  const handleNse52WeekUpload = async () => {
    if (!nse52WeekFile) {
      setMessage('Please select a NSE 52-week CSV file');
      return;
    }
    
    setLoading(true);
    try {
      const result = await nseApi.upload52Week(nse52WeekFile);
      setMessage(`Processing ${result.symbolsProcessed} symbols from uploaded CSV`);
      setNse52WeekFile(null);
    } catch (error) {
      setMessage('Error uploading NSE 52-week CSV: ' + error.message);
    }
    setLoading(false);
  };

  const handleSendWeekHigh = async () => {
    setLoading(true);
    try {
      const result = await nseApi.sendWeekHigh();
      setMessage(`Sent 52-week high CSV to Telegram (${result.stocksFound} stocks)`);
    } catch (error) {
      setMessage('Error sending 52-week high: ' + error.message);
    }
    setLoading(false);
  };

  const handleSendWeekLow = async () => {
    setLoading(true);
    try {
      const result = await nseApi.sendWeekLow();
      setMessage(`Sent 52-week low CSV to Telegram (${result.stocksFound} stocks)`);
    } catch (error) {
      setMessage('Error sending 52-week low: ' + error.message);
    }
    setLoading(false);
  };

  const handleSendBoth = async () => {
    setLoading(true);
    try {
      await nseApi.sendBoth();
      setMessage('Sent both 52-week high and low CSVs to Telegram');
    } catch (error) {
      setMessage('Error sending both: ' + error.message);
    }
    setLoading(false);
  };

  const handleWeeklyCloseBreakout = async () => {
    setLoading(true);
    try {
      await nseApi.scanWeeklyCloseBreakout();
      setMessage('52-week high weekly close breakout scan completed');
    } catch (error) {
      setMessage('Error scanning weekly close breakout: ' + error.message);
    }
    setLoading(false);
  };

  const getDisplayData = () => {
    return activeTab === 'upcoming' ? upcomingIpos : allIpos;
  };

  const formatDate = (dateStr) => {
    if (!dateStr) return 'N/A';
    return new Date(dateStr).toLocaleDateString('en-IN', {
      day: '2-digit',
      month: 'short',
      year: 'numeric'
    });
  };

  const formatCurrency = (num) => {
    if (!num) return 'N/A';
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      maximumFractionDigits: 2
    }).format(num);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>📈 IPO Scanner</h2>
      
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
            onClick={handleSync} 
            disabled={loading}
            style={styles.button}
          >
            🔄 Sync from ipoalerts.in
          </button>
          <button 
            onClick={handleListingOpenAlert} 
            disabled={loading}
            style={styles.button}
          >
            📊 Listing Open Alert
          </button>
          <button 
            onClick={handleListingEodAlert} 
            disabled={loading}
            style={styles.button}
          >
            📉 Listing EOD Alert
          </button>
          <button 
            onClick={handleUpcomingSummary} 
            disabled={loading}
            style={styles.button}
          >
            📅 Upcoming Summary
          </button>
          <button 
            onClick={handleStrategyScan} 
            disabled={loading}
            style={styles.button}
          >
            🔍 Strategy Scan
          </button>
          <button 
            onClick={loadData} 
            disabled={loading}
            style={styles.button}
          >
            🔄 Refresh
          </button>
        </div>
      </div>

      {/* CSV Upload Section */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Upload NSE IPO CSV</h3>
        <input
          type="file"
          accept=".csv"
          onChange={(e) => setCsvFile(e.target.files[0])}
          style={styles.fileInput}
        />
        <button 
          onClick={handleCsvUpload} 
          disabled={loading || !csvFile}
          style={styles.button}
        >
          {loading ? 'Processing...' : 'Upload CSV'}
        </button>
      </div>

      {/* NSE 52-Week High Section */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>NSE 52-Week High/Low Scanner</h3>
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
            onClick={handleWeeklyCloseBreakout} 
            disabled={loading}
            style={styles.button}
          >
            🔍 Weekly Close Breakout
          </button>
        </div>
        <div style={{ marginTop: '15px' }}>
          <input
            type="file"
            accept=".csv"
            onChange={(e) => setNse52WeekFile(e.target.files[0])}
            style={styles.fileInput}
          />
          <button 
            onClick={handleNse52WeekUpload} 
            disabled={loading || !nse52WeekFile}
            style={styles.button}
          >
            {loading ? 'Processing...' : 'Upload & Scan 52-Week CSV'}
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div style={styles.tabs}>
        <button
          onClick={() => setActiveTab('upcoming')}
          style={{
            ...styles.tab,
            ...(activeTab === 'upcoming' ? styles.activeTab : {})
          }}
        >
          UPCOMING ({upcomingIpos.length})
        </button>
        <button
          onClick={() => setActiveTab('all')}
          style={{
            ...styles.tab,
            ...(activeTab === 'all' ? styles.activeTab : {})
          }}
        >
          ALL ({allIpos.length})
        </button>
      </div>

      {/* Data Table */}
      <div style={styles.tableContainer}>
        <table style={styles.table}>
          <thead>
            <tr>
              <th style={styles.th}>Company</th>
              <th style={styles.th}>Symbol</th>
              <th style={styles.th}>Listing Date</th>
              <th style={styles.th}>Price Band</th>
              <th style={styles.th}>Issue Size</th>
              <th style={styles.th}>Status</th>
            </tr>
          </thead>
          <tbody>
            {getDisplayData().map((ipo, index) => (
              <tr key={index} style={styles.tr}>
                <td style={styles.td}><strong>{ipo.companyName || ipo.name || 'N/A'}</strong></td>
                <td style={styles.td}>{ipo.symbol || 'N/A'}</td>
                <td style={styles.td}>{formatDate(ipo.listingDate)}</td>
                <td style={styles.td}>
                  {ipo.priceBandMin && ipo.priceBandMax 
                    ? `₹${ipo.priceBandMin} - ₹${ipo.priceBandMax}`
                    : 'N/A'}
                </td>
                <td style={styles.td}>{formatCurrency(ipo.issueSize)}</td>
                <td style={styles.td}>
                  <span style={{
                    ...styles.status,
                    ...(ipo.status === 'LISTED' ? styles.statusListed : {}),
                    ...(ipo.status === 'UPCOMING' ? styles.statusUpcoming : {})
                  }}>
                    {ipo.status || 'N/A'}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {getDisplayData().length === 0 && (
          <div style={styles.emptyState}>No IPO data available</div>
        )}
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
  fileInput: {
    marginBottom: '10px',
    padding: '8px',
    border: '1px solid #ddd',
    borderRadius: '6px'
  },
  button: {
    padding: '10px 20px',
    background: '#ff6b6b',
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
  tabs: {
    display: 'flex',
    gap: '5px',
    marginBottom: '15px',
    flexWrap: 'wrap'
  },
  tab: {
    padding: '8px 16px',
    background: '#e0e0e0',
    border: 'none',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '13px',
    fontWeight: '500'
  },
  activeTab: {
    background: '#ff6b6b',
    color: 'white'
  },
  tableContainer: {
    overflowX: 'auto'
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse',
    fontSize: '13px'
  },
  th: {
    background: '#ff6b6b',
    color: 'white',
    padding: '10px',
    textAlign: 'left',
    fontWeight: '600',
    whiteSpace: 'nowrap'
  },
  tr: {
    borderBottom: '1px solid #e0e0e0'
  },
  td: {
    padding: '10px',
    color: '#333'
  },
  status: {
    padding: '4px 8px',
    borderRadius: '4px',
    fontSize: '12px',
    fontWeight: '600'
  },
  statusListed: {
    background: '#4caf50',
    color: 'white'
  },
  statusUpcoming: {
    background: '#ff9800',
    color: 'white'
  },
  emptyState: {
    textAlign: 'center',
    padding: '30px',
    color: '#999',
    fontSize: '14px'
  }
};

export default IpoScanner;
